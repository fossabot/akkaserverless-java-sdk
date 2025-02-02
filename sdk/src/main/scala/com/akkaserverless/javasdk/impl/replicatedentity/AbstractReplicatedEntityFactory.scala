/*
 * Copyright 2021 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.akkaserverless.javasdk.impl.replicatedentity

import com.akkaserverless.javasdk.replicatedentity._
import com.akkaserverless.javasdk.impl.AnySupport

trait AbstractReplicatedEntityFactory extends ReplicatedDataFactory {
  protected def anySupport: AnySupport
  protected def newEntity[E <: InternalReplicatedData](entity: E): E
  override def newCounter(): ReplicatedCounter = newEntity(new ReplicatedCounterImpl)
  override def newReplicatedCounterMap[K](): ReplicatedCounterMap[K] =
    new ReplicatedCounterMap(newReplicatedMap[K, ReplicatedCounter]())
  override def newReplicatedSet[T](): ReplicatedSet[T] = newEntity(new ReplicatedSetImpl[T](anySupport))
  override def newRegister[T](value: T): ReplicatedRegister[T] = {
    val register = newEntity(new ReplicatedRegisterImpl[T](anySupport))
    if (value != null) {
      register.set(value)
    }
    register
  }
  override def newReplicatedRegisterMap[K, V](): ReplicatedRegisterMap[K, V] =
    new ReplicatedRegisterMap(newReplicatedMap[K, ReplicatedRegister[V]]())
  override def newReplicatedMap[K, V <: ReplicatedData](): ReplicatedMap[K, V] =
    newEntity(new ReplicatedMapImpl[K, InternalReplicatedData](anySupport)).asInstanceOf[ReplicatedMap[K, V]]
  override def newVote(): Vote = newEntity(new VoteImpl)
}
