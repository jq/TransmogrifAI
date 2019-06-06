package com.salesforce.op.stages.impl.classification

import com.salesforce.op.UID
import com.salesforce.op.features.types.{OPVector, RealMap, RealNN}
import org.apache.spark.ml.tree.RichNode._
import org.apache.spark.ml.tree.RichOldNode._
import com.salesforce.op.stages.base.binary.{BinaryEstimator, BinaryModel}
import org.apache.spark.sql.functions._
import org.apache.spark.ml.linalg
import org.apache.spark.ml.param.{DoubleParam, ParamValidators}
import org.apache.spark.ml.regression.DecisionTreeRegressionModel
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.{Dataset, Encoder, Encoders}
import org.apache.spark.sql.expressions.Window

class QuantileRegressionRF(uid: String = UID[QuantileRegressionRF], operationName: String = "quantiles",
  val trees: Array[DecisionTreeRegressionModel])
  extends BinaryEstimator[RealNN, OPVector, RealMap](operationName = operationName, uid = uid) {

  val percentageLevel = new DoubleParam(
  parent = this, name = "percentageLevel",
  doc = "level of prediction interval",
  isValid = ParamValidators.inRange(lowerBound = 0.0, upperBound = 1.0)
  )
  final def setPercentageLevel(v: Double): this.type = set(percentageLevel, v)
  final def getPercentageLevel: Double = $(percentageLevel)
  setDefault(percentageLevel -> 0.95)

  implicit val encoderLeafNode: Encoder[(Option[Double], Array[Int])] = Encoders.kryo[(Option[Double], Array[Int])]

  override def fitFn(dataset: Dataset[(Option[Double], linalg.Vector)]): BinaryModel[RealNN, OPVector, RealMap] = {

    val lowerLevel = (1 - getPercentageLevel) / 2.0
    val upperLevel = (1 + getPercentageLevel) / 2.0

    val leaves = dataset.map { case (l, f) =>
        l -> trees.map { case tree =>
          val node = tree.rootNode
          val oldNode = node.toOld(1)
          val leafID = oldNode.predictImplIdx(f)
          leafID
        }
      }

    new QuantileRegressionRFModels(leaves, trees, lowerLevel, upperLevel, operationName, uid)

  }
}


class QuantileRegressionRFModels(leaves: Dataset[(Option[Double], Array[Int])],
  trees: Array[DecisionTreeRegressionModel], lowerLevel: Double, upperLevel: Double, operationName: String, uid: String)
  extends BinaryModel[RealNN, OPVector, RealMap](operationName = operationName, uid = uid) {

  private implicit val encoder: Encoder[(Double, Option[Double])] = ExpressionEncoder[(Double, Option[Double])]()
  private implicit val encoderInt: Encoder[Int] = ExpressionEncoder[Int]()

  override def transformFn: (RealNN, OPVector) => RealMap = {

    val T = trees.length
    val leaveSizes = (0 until T).map(i => leaves.map(_._2(i)).rdd.countByValue())

    (l: RealNN, f: OPVector) => {
      val pred_leaves = trees.map(_.rootNode.toOld(1).predictImplIdx(f.value))
      val weightsRDD = leaves.rdd.map { case (y, y_leaves) => y_leaves.zip(pred_leaves).zipWithIndex.map {
        case ((l1, l2), i) =>
          if (l1 == l2) {
            1.0 / leaveSizes(i).get(l1).getOrElse(throw new Exception(s"fail to find leave $l1 in Tree # $i." +
              s" Available leaves are ${leaveSizes(i).toSeq}"))
          } else 0.0
      }.sum / T -> y
      }

      import leaves.sparkSession.implicits._
      val weights = weightsRDD.toDF()
      val Array(wName, yName) = weights.columns

      val cumF = weights.sort(yName).select(sum(col(wName)).over(Window.orderBy(yName)
        .rowsBetween(Window.unboundedPreceding, Window.currentRow)),
        col(yName)).as[(Double, Option[Double])]

      val qLower = cumF.filter {
        _._1 >= lowerLevel
      }.first()._2
      val qUpper = cumF.filter {
        _._1 >= upperLevel
      }.first()._2

      new RealMap(Map("lowerQuantile" -> qLower.get, "upperQuantile" -> qUpper.get))
    }
  }
}
