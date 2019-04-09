/*
 * Copyright 2019 Jungtaek Lim "<kabhwan@gmail.com>"
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.state

import java.util.UUID

import org.apache.spark.internal.Logging
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.execution.streaming._
import org.apache.spark.sql.state.StateSchemaExtractor.{StateKind, StateSchemaInfo}
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.StructType

class StateSchemaExtractor(spark: SparkSession) extends Logging {

  def extract(query: DataFrame): Seq[StateSchemaInfo] = {
    require(query.isStreaming, "Given query is not a streaming query!")

    val queryExecution = new IncrementalExecution(spark, query.logicalPlan, OutputMode.Update(),
      "<unknown>", UUID.randomUUID(), 0, OffsetSeqMetadata(0, 0))

    // TODO: handle Streaming Join (if possible), etc.
    queryExecution.executedPlan.collect {
      case store: StateStoreSaveExec =>
        val stateFormatVersion = store.stateFormatVersion
        val keySchema = store.keyExpressions.toStructType
        val valueSchema = store.stateManager.getStateValueSchema
        store.stateInfo match {
          case Some(stInfo) =>
            val operatorId = stInfo.operatorId
            StateSchemaInfo(operatorId, StateKind.StreamingAggregation,
              stateFormatVersion, keySchema, valueSchema)

          case None => throw new IllegalStateException("State information not set!")
        }

      case store: FlatMapGroupsWithStateExec =>
        val stateFormatVersion = store.stateFormatVersion
        val keySchema = store.groupingAttributes.toStructType
        val valueSchema = store.stateManager.stateSchema
        store.stateInfo match {
          case Some(stInfo) =>
            val operatorId = stInfo.operatorId
            StateSchemaInfo(operatorId, StateKind.FlatMapGroupsWithState,
              stateFormatVersion, keySchema, valueSchema)

          case None => throw new IllegalStateException("State information not set!")
        }
    }
  }

}

object StateSchemaExtractor {
  object StateKind extends Enumeration {
    val StreamingAggregation, StreamingJoin, FlatMapGroupsWithState = Value
  }

  case class StateSchemaInfo(
      opId: Long,
      stateKind: StateKind.Value,
      formatVersion: Int,
      keySchema: StructType,
      valueSchema: StructType)
}