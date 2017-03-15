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

package org.apache.spark.sql.catalyst.expressions.aggregate

import java.{lang => jl}

import scala.util.Random

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult.TypeCheckFailure
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.sketch.CountMinSketch

/**
 * Unit test suite for the count-min sketch SQL aggregate funciton [[CountMinSketchAgg]].
 */
class CountMinSketchAggSuite extends SparkFunSuite {
  private val childExpression = BoundReference(0, IntegerType, nullable = true)
  private val epsOfTotalCount = 0.0001
  private val confidence = 0.99
  private val seed = 42
  private val rand = new Random(seed)

  /** Creates a count-min sketch aggregate expression, using the child expression defined above. */
  private def cms(eps: jl.Double, confidence: jl.Double, seed: jl.Integer): CountMinSketchAgg = {
    new CountMinSketchAgg(
      child = childExpression,
      epsExpression = Literal(eps, DoubleType),
      confidenceExpression = Literal(confidence, DoubleType),
      seedExpression = Literal(seed, IntegerType))
  }

  /**
   * Creates a new test case that compares our aggregate function with a reference implementation
   * (using the underlying [[CountMinSketch]]).
   *
   * This works by splitting the items into two separate groups, aggregates them, and then merges
   * the two groups back (to emulate partial aggregation), and then compares the result with
   * that generated by [[CountMinSketch]] directly. This assumes insertion order does not impact
   * the result in count-min sketch.
   */
  private def testDataType[T](dataType: DataType, items: Seq[T]): Unit = {
    test("test data type " + dataType) {
      val agg = new CountMinSketchAgg(BoundReference(0, dataType, nullable = true),
        Literal(epsOfTotalCount), Literal(confidence), Literal(seed))
      assert(!agg.nullable)

      val (seq1, seq2) = items.splitAt(items.size / 2)
      val buf1 = addToAggregateBuffer(agg, seq1)
      val buf2 = addToAggregateBuffer(agg, seq2)

      val sketch = agg.createAggregationBuffer()
      agg.merge(sketch, buf1)
      agg.merge(sketch, buf2)

      // Validate cardinality estimation against reference implementation.
      val referenceSketch = CountMinSketch.create(epsOfTotalCount, confidence, seed)
      items.foreach { item =>
        referenceSketch.add(item match {
          case u: UTF8String => u.getBytes
          case _ => item
        })
      }

      items.foreach { item =>
        withClue(s"For item $item") {
          val itemToTest = item match {
            case u: UTF8String => u.getBytes
            case _ => item
          }
          assert(referenceSketch.estimateCount(itemToTest) == sketch.estimateCount(itemToTest))
        }
      }
    }

    def addToAggregateBuffer[T](agg: CountMinSketchAgg, items: Seq[T]): CountMinSketch = {
      val buf = agg.createAggregationBuffer()
      items.foreach { item => agg.update(buf, InternalRow(item)) }
      buf
    }
  }

  testDataType[Byte](ByteType, Seq.fill(100) { rand.nextInt(10).toByte })

  testDataType[Short](ShortType, Seq.fill(100) { rand.nextInt(10).toShort })

  testDataType[Int](IntegerType, Seq.fill(100) { rand.nextInt(10) })

  testDataType[Long](LongType, Seq.fill(100) { rand.nextInt(10) })

  testDataType[UTF8String](StringType, Seq.fill(100) { UTF8String.fromString(rand.nextString(1)) })

  testDataType[Array[Byte]](BinaryType, Seq.fill(100) { rand.nextString(1).getBytes() })

  test("serialize and de-serialize") {
    // Check empty serialize and de-serialize
    val agg = cms(epsOfTotalCount, confidence, seed)
    val buffer = CountMinSketch.create(epsOfTotalCount, confidence, seed)
    assert(buffer.equals(agg.deserialize(agg.serialize(buffer))))

    // Check non-empty serialize and de-serialize
    val random = new Random(31)
    for (i <- 0 until 10) {
      buffer.add(random.nextInt(100))
    }
    assert(buffer.equals(agg.deserialize(agg.serialize(buffer))))
  }

  test("fails analysis if eps, confidence or seed provided is not foldable") {
    val wrongEps = new CountMinSketchAgg(
      childExpression,
      epsExpression = AttributeReference("a", DoubleType)(),
      confidenceExpression = Literal(confidence),
      seedExpression = Literal(seed))
    val wrongConfidence = new CountMinSketchAgg(
      childExpression,
      epsExpression = Literal(epsOfTotalCount),
      confidenceExpression = AttributeReference("b", DoubleType)(),
      seedExpression = Literal(seed))
    val wrongSeed = new CountMinSketchAgg(
      childExpression,
      epsExpression = Literal(epsOfTotalCount),
      confidenceExpression = Literal(confidence),
      seedExpression = AttributeReference("c", IntegerType)())

    Seq(wrongEps, wrongConfidence, wrongSeed).foreach { wrongAgg =>
      assertResult(
        TypeCheckFailure("The eps, confidence or seed provided must be a literal or foldable")) {
        wrongAgg.checkInputDataTypes()
      }
    }
  }

  test("fails analysis if parameters are invalid") {
    // parameters are null
    val wrongEps = cms(null, confidence, seed)
    val wrongConfidence = cms(epsOfTotalCount, null, seed)
    val wrongSeed = cms(epsOfTotalCount, confidence, null)

    Seq(wrongEps, wrongConfidence, wrongSeed).foreach { wrongAgg =>
      assertResult(TypeCheckFailure("The eps, confidence or seed provided should not be null")) {
        wrongAgg.checkInputDataTypes()
      }
    }

    // parameters are out of the valid range
    Seq(0.0, -1000.0).foreach { invalidEps =>
      val invalidAgg = cms(invalidEps, confidence, seed)
      assertResult(
        TypeCheckFailure(s"Relative error must be positive (current value = $invalidEps)")) {
        invalidAgg.checkInputDataTypes()
      }
    }

    Seq(0.0, 1.0, -2.0, 2.0).foreach { invalidConfidence =>
      val invalidAgg = cms(epsOfTotalCount, invalidConfidence, seed)
      assertResult(TypeCheckFailure(
        s"Confidence must be within range (0.0, 1.0) (current value = $invalidConfidence)")) {
        invalidAgg.checkInputDataTypes()
      }
    }
  }

  test("null handling") {
    def isEqual(result: Any, other: CountMinSketch): Boolean = {
      other.equals(CountMinSketch.readFrom(result.asInstanceOf[Array[Byte]]))
    }

    val agg = cms(epsOfTotalCount, confidence, seed)
    val emptyCms = CountMinSketch.create(epsOfTotalCount, confidence, seed)
    val buffer = new GenericInternalRow(new Array[Any](1))
    agg.initialize(buffer)
    // Empty aggregation buffer
    assert(isEqual(agg.eval(buffer), emptyCms))

    // Empty input row
    agg.update(buffer, InternalRow(null))
    assert(isEqual(agg.eval(buffer), emptyCms))

    // Add some non-empty row
    agg.update(buffer, InternalRow(0))
    assert(!isEqual(agg.eval(buffer), emptyCms))
  }
}
