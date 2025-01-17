/*
 * Tencent is pleased to support the open source community by making Angel available.
 *
 * Copyright (C) 2017-2018 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in 
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */


package com.tencent.angel.ml.core.network.layers

import com.tencent.angel.client.AngelClient
import com.tencent.angel.conf.AngelConf
import com.tencent.angel.ml.core.conf.SharedConf
import com.tencent.angel.ml.feature.LabeledData
import com.tencent.angel.ml.math2.matrix.Matrix
import com.tencent.angel.ml.math2.vector.Vector
import com.tencent.angel.ml.matrix.MatrixContext
import com.tencent.angel.ml.core.utils.PSMatrixUtils
import com.tencent.angel.model.ModelSaveContext
import org.apache.commons.logging.{Log, LogFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class TimeStats(
                 var forwardTime: Long = 0,
                 var backwardTime: Long = 0,
                 var calGradTime: Long = 0,
                 var pullParamsTime: Long = 0,
                 var pushParamsTime: Long = 0,
                 var updateTime: Long = 0) extends Serializable {
  val LOG: Log = LogFactory.getLog(classOf[TimeStats])

  def summary(): String = {
    val summaryString = s"\nSummary: \n\t" +
      s"forwardTime = $forwardTime, \n\tbackwardTime = $backwardTime, \n\t" +
      s"calGradTime = $calGradTime, \n\tpullParamsTime = $pullParamsTime, \n\t" +
      s"pushParamsTime = $pushParamsTime, \n\tupdateTime = $updateTime"

    LOG.info(summaryString)
    summaryString
  }
}

class AngelGraph(val placeHolder: PlaceHolder, val conf: SharedConf) extends Serializable {

  def this(placeHolder: PlaceHolder) = this(placeHolder, SharedConf.get())

  val LOG: Log = LogFactory.getLog(classOf[AngelGraph])

  private val inputLayers = new ListBuffer[InputLayer]()
  private var lossLayer: LossLayer = _
  private val trainableLayer = new ListBuffer[Trainable]()
  @transient private val matrixCtxs = new ArrayBuffer[MatrixContext]()
  val timeStats = new TimeStats()
  var taskNum: Int = _

  def addInput(layer: InputLayer): Unit = {
    inputLayers.append(layer)
  }

  def setOutput(layer: LossLayer): Unit = {
    lossLayer = layer
  }

  def getOutputLayer: LossLayer = {
    lossLayer
  }

  def addTrainable(layer: Trainable): Unit = {
    trainableLayer.append(layer)
  }

  def getTrainable: ListBuffer[Trainable] = {
    trainableLayer
  }

  def addMatrixCtx(mc: MatrixContext): Unit = {
    matrixCtxs.append(mc)
  }

  def getMatrixCtx(): Seq[MatrixContext] = matrixCtxs

  private def deepFirstDown(layer: Layer)(predicate: Layer => Boolean, action: Layer => Unit): Unit = {
    if (predicate(layer)) {
      action(layer)
      layer.input.foreach { lowerLayer =>
        deepFirstDown(lowerLayer)(predicate, action)
      }
    }
  }

  def setState(predicate: Layer => Boolean, status: STATUS.STATUS): Unit = {
    deepFirstDown(lossLayer.asInstanceOf[Layer])(
      predicate, (layer: Layer) => layer.status = status
    )
  }

  def feedData(data: Array[LabeledData]): Unit = {
    deepFirstDown(lossLayer.asInstanceOf[Layer])(
      (lay: Layer) => lay.status != STATUS.Null,
      (lay: Layer) => lay.status = STATUS.Null
    )

    placeHolder.feedData(data)
  }

  def predict(): Matrix = {
    val start = System.currentTimeMillis()
    val res = lossLayer.predict()
    timeStats.forwardTime += (System.currentTimeMillis() - start)
    res
  }

  def calLoss(): Double = {
    lossLayer.calLoss()
  }

  def calBackward(): Unit = {
    val start = System.currentTimeMillis()
    inputLayers.foreach { layer => layer.calBackward() }
    timeStats.backwardTime += (System.currentTimeMillis() - start)
  }

  def pullParams(): Unit = {
    val start = System.currentTimeMillis()
    //    val pullFuture = Future.sequence(trainableLayer.map{ layer => Future { layer.pullParams() }})
    //    Await.result(pullFuture, Duration.Inf)
    trainableLayer.foreach { layer => layer.pullParams() }

    timeStats.pullParamsTime += (System.currentTimeMillis() - start)
  }

  def pushGradient(): Unit = {
    val start = System.currentTimeMillis()
    //    val pushFuture = Future.sequence(trainableLayer.map{ layer => Future { layer.pushGradient() } })
    //    Await.result(pushFuture, Duration.Inf)
    trainableLayer.foreach(layer => layer.pushGradient())
    timeStats.pushParamsTime += (System.currentTimeMillis() - start)
  }

  def update(epoch: Int, batchSize: Int): Unit = {
    val start = System.currentTimeMillis()
    val updateFuture = trainableLayer.map (layer => layer.update(epoch, batchSize))
    //Await.result(updateFuture, Duration.Inf)
    for(future <- updateFuture) future.get
    timeStats.updateTime += (System.currentTimeMillis() - start)
  }

  def init(taskId: Int = 0, idxsVector: Vector = null): Unit = {
    val updateFuture = Future.sequence(trainableLayer.map { layer => Future {
      layer.init(taskId, idxsVector)
    }
    })
    Await.result(updateFuture, Duration.Inf)
  }

  def loadModel(client: AngelClient): Unit = {
    trainableLayer.foreach { layer => layer.loadParams(client) }
    client.createMatrices()
    client.load()
  }

  def loadModel(): Unit = {
    PSMatrixUtils.createPSMatrix(matrixCtxs)
  }

  def saveModel(client: AngelClient): Unit = {
    val saveContext = new ModelSaveContext
    saveContext.setSavePath(client.getConf.get(AngelConf.ANGEL_SAVE_MODEL_PATH))
    trainableLayer.foreach { layer => layer.saveParams(saveContext) }
    client.save(saveContext)
  }

  def saveModel(): Unit = {
    val saveContext = new ModelSaveContext
  }

  def setLR(lr: Double): Unit = {
    trainableLayer.foreach { trainable =>
      trainable.optimizer.setLearningRate(lr)
    }
  }

  override def toString: String = {
    val str = new StringBuilder
    deepFirstDown(lossLayer.asInstanceOf[Layer])(_ => true, layer => str.append(layer.toString + "\n"))
    str.toString()
  }
}
