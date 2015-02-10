/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.spark.mllib.neuralNetwork

import java.util.Random

import breeze.linalg.{DenseVector => BDV, Matrix => BM, DenseMatrix => BDM,
Axis => brzAxis, sum => brzSum, max => brzMax}

import org.apache.spark.Logging
import org.apache.spark.mllib.linalg.{DenseMatrix => SDM, Matrix => SM,
Vector => SV, Vectors, Matrices, BLAS}

import NNUtil._

private[mllib] trait Layer extends Serializable {

  def weight: BDM[Double]

  def bias: BDV[Double]

  def numIn = weight.cols

  def numOut = weight.rows

  def layerType: String

  protected lazy val rand: Random = new Random()

  def setSeed(seed: Long): Unit = {
    rand.setSeed(seed)
  }

  def forward(input: BM[Double]): BDM[Double] = {
    val batchSize = input.cols
    val output: BDM[Double] = weight * input
    for (i <- 0 until batchSize) {
      output(::, i) :+= bias
    }
    computeNeuron(output)
    output
  }

  def backward(input: BDM[Double], delta: BDM[Double]): (BDM[Double], BDV[Double]) = {
    val gradWeight: BDM[Double] = delta * input.t
    val gradBias = brzSum(delta, brzAxis._1)
    (gradWeight, gradBias)
  }

  def outputError(output: BDM[Double], label: BM[Double]): BDM[Double] = {
    val delta: BDM[Double] = if (label.isInstanceOf[BDM[Double]]) {
      output - label.asInstanceOf[BDM[Double]]
    } else {
      output - label.toDenseMatrix
    }

    computeNeuronPrimitive(delta, output)
    delta
  }

  def previousError(
    input: BM[Double],
    previousLayer: Layer,
    currentDelta: BDM[Double]): BDM[Double] = {
    val preDelta = weight.t * currentDelta
    previousLayer.computeNeuronPrimitive(preDelta, input)
    preDelta
  }

  def computeNeuron(temp: BDM[Double]): Unit

  def computeNeuronPrimitive(temp: BDM[Double], output: BM[Double]): Unit

  protected[mllib] def sample(out: BDM[Double]): BDM[Double] = out
}

private[mllib] class SigmoidLayer(
  val weight: BDM[Double],
  val bias: BDV[Double]) extends Layer with Logging {

  def this(numIn: Int, numOut: Int) {
    this(initUniformDistWeight(numIn, numOut, 4D * math.sqrt(6D / (numIn + numOut))),
      initializeBias(numOut))
  }

  override def layerType: String = "Sigmoid"

  override def computeNeuron(temp: BDM[Double]): Unit = {
    for (i <- 0 until temp.rows) {
      for (j <- 0 until temp.cols) {
        temp(i, j) = sigmoid(temp(i, j))
      }
    }
  }

  override def computeNeuronPrimitive(
    temp: BDM[Double],
    output: BM[Double]): Unit = {
    for (i <- 0 until temp.rows) {
      for (j <- 0 until temp.cols) {
        temp(i, j) = temp(i, j) * sigmoidPrimitive(output(i, j))
      }
    }
  }

  protected[mllib] override def sample(input: BDM[Double]): BDM[Double] = {
    input.map(v => if (rand.nextDouble() < v) 1D else 0D)
  }
}

private[mllib] class TanhLayer(
  val weight: BDM[Double],
  val bias: BDV[Double]) extends Layer with Logging {

  def this(numIn: Int, numOut: Int) {
    this(initUniformDistWeight(numIn, numOut, math.sqrt(6D / (numIn + numOut))),
      initializeBias(numOut))
  }

  override def layerType: String = "Tanh"

  override def computeNeuron(temp: BDM[Double]): Unit = {
    for (i <- 0 until temp.rows) {
      for (y <- 0 until temp.cols) {
        temp(i, y) = tanh(temp(i, y))
      }
    }
  }

  def computeNeuronPrimitive(
    temp: BDM[Double],
    output: BM[Double]): Unit = {
    for (i <- 0 until temp.rows) {
      for (y <- 0 until temp.cols) {
        temp(i, y) = temp(i, y) * tanhPrimitive(output(i, y))
      }
    }
  }

  protected[mllib] override def sample(input: BDM[Double]): BDM[Double] = {
    input.map(v => if (rand.nextDouble() < v) 1D else 0D)
  }
}

private[mllib] class SoftMaxLayer(
  val weight: BDM[Double],
  val bias: BDV[Double]) extends Layer with Logging {

  def this(numIn: Int, numOut: Int) {
    this(initializeWeight(numIn, numOut), initializeBias(numOut))
  }

  override def layerType: String = "SoftMax"

  override def computeNeuron(temp: BDM[Double]): Unit = {
    val brzTemp = temp
    for (col <- 0 until brzTemp.cols) {
      softMax(brzTemp(::, col))
    }
  }

  def softMax(temp: BDV[Double]): Unit = {
    // val max = brzMax(temp)
    var sum = 0D
    for (i <- 0 until temp.length) {
      // temp(i) = Math.exp(temp(i) - max)
      temp(i) = Math.exp(temp(i))
      sum += temp(i)
    }
    temp :/= sum
  }

  override def computeNeuronPrimitive(
    temp: BDM[Double],
    output: BM[Double]): Unit = {
    // See: http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.49.6403

    //  for (i <- 0 until temp.numRows) {
    //    for (j <- 0 until temp.numCols) {
    //      temp(i, j) = temp(i, j) * softMaxPrimitive(output(i, j))
    //    }
    //  }
  }

  override protected[mllib] def sample(out: BDM[Double]): BDM[Double] = {
    val brzOut = out
    for (j <- 0 until brzOut.cols) {
      val v = brzOut(::, j)
      var sum = 0D
      var index = 0
      var find = false
      val s = rand.nextDouble()
      while (!find && index < v.length) {
        sum += v(index)
        if (sum >= s) {
          find = true
        } else {
          index += 1
        }
      }
      v :*= 0D
      index = if (find) index else index - 1
      v(index) = 1
    }
    out
  }
}

private[mllib] class NoisyReLULayer(
  val weight: BDM[Double],
  val bias: BDV[Double]) extends Layer with Logging {
  def this(numIn: Int, numOut: Int) {
    this(initUniformDistWeight(numIn, numOut, 0D, 0.01),
      initializeBias(numOut))
  }

  override def layerType: String = "NoisyReLU"

  private def nReLu(tmp: BDM[Double]): Unit = {
    for (i <- 0 until tmp.rows) {
      for (j <- 0 until tmp.cols) {
        val v = tmp(i, j)
        val sd = sigmoid(v)
        val x = v + sd * rand.nextGaussian()
        tmp(i, j) = math.max(0, x)
      }
    }
  }

  override def computeNeuron(temp: BDM[Double]): Unit = {
    nReLu(temp)
  }

  override def computeNeuronPrimitive(
    temp: BDM[Double],
    output: BM[Double]): Unit = {
    for (i <- 0 until temp.rows) {
      for (j <- 0 until temp.cols)
        if (output(i, j) <= 0) {
          temp(i, j) = 0
        }
    }
  }
}

private[mllib] class ReLuLayer(
  val weight: BDM[Double],
  val bias: BDV[Double]) extends Layer with Logging {

  def this(numIn: Int, numOut: Int) {
    this(initUniformDistWeight(numIn, numOut, 0.0, 0.01),
      initializeBias(numOut))
  }

  override def layerType: String = "ReLu"

  private def relu(tmp: BDM[Double]): Unit = {
    for (i <- 0 until tmp.rows) {
      for (j <- 0 until tmp.cols) {
        tmp(i, j) = math.max(0, tmp(i, j))
      }
    }
  }

  override def computeNeuron(temp: BDM[Double]): Unit = {
    relu(temp)
  }

  override def computeNeuronPrimitive(temp: BDM[Double], output: BM[Double]): Unit = {
    for (i <- 0 until temp.rows) {
      for (j <- 0 until temp.cols)
        if (output(i, j) <= 0) {
          temp(i, j) = 0
        }
    }
  }

  override protected[mllib] def sample(input: BDM[Double]): BDM[Double] = {
    input.map { v =>
      val sd = sigmoid(v, 32)
      val x = v + sd * rand.nextGaussian()
      math.max(0, x)
    }
  }
}

private[mllib] class SoftPlusLayer(
  val weight: BDM[Double],
  val bias: BDV[Double]) extends Layer with Logging {
  def this(numIn: Int, numOut: Int) {
    this(initUniformDistWeight(numIn, numOut, 0D, 0.01),
      initializeBias(numOut))
  }

  override def layerType: String = "SoftPlus"

  override def computeNeuron(temp: BDM[Double]): Unit = {
    for (i <- 0 until temp.rows) {
      for (j <- 0 until temp.cols) {
        temp(i, j) = softplus(temp(i, j))
      }
    }
  }

  override def computeNeuronPrimitive(temp: BDM[Double], output: BM[Double]): Unit = {
    for (i <- 0 until temp.rows) {
      for (j <- 0 until temp.cols) {
        temp(i, j) *= softplusPrimitive(output(i, j))
      }
    }
  }

  override protected[mllib] def sample(input: BDM[Double]): BDM[Double] = {
    input.map { v =>
      val sd = sigmoid(v)
      val x = v + sd * rand.nextGaussian()
      // val rng = new NormalDistribution(rand, 0, sd + 1e-23, 1e-9)
      // val x = v + rng.sample()
      math.max(0, x)
    }
  }
}

private[mllib] class Identity(
  val weight: BDM[Double],
  val bias: BDV[Double]) extends Layer with Logging {

  def this(numIn: Int, numOut: Int) {
    this(initUniformDistWeight(numIn, numOut, 0D, 0.01),
      initializeBias(numOut))
  }

  override def layerType: String = "Identity"

  override def computeNeuron(tmp: BDM[Double]): Unit = {}

  override def computeNeuronPrimitive(temp: BDM[Double], output: BM[Double]): Unit = {}

  override protected[mllib] def sample(input: BDM[Double]): BDM[Double] = {
    input.map(v => v + rand.nextGaussian())
  }
}

private[mllib] object Layer {

  def initializeLayer(weight: BDM[Double], bias: BDV[Double], layerType: String): Layer = {
    layerType match {
      case "SoftPlus" =>
        new SoftPlusLayer(weight, bias)
      case "ReLu" =>
        new ReLuLayer(weight, bias)
      case "NoisyReLU" =>
        new NoisyReLULayer(weight, bias)
      case "SoftMax" =>
        new SoftMaxLayer(weight, bias)
      case "Tanh" =>
        new TanhLayer(weight, bias)
      case "Sigmoid" =>
        new SigmoidLayer(weight, bias)
      case "Identity" =>
        new Identity(weight, bias)
      case _ =>
        throw new IllegalArgumentException("layerType is not correct")
    }
  }
}
