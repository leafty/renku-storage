/*
 * Copyright 2017 - Swiss Data Science Center (SDSC)
 * A partnership between École Polytechnique Fédérale de Lausanne (EPFL) and
 * Eidgenössische Technische Hochschule Zürich (ETHZ).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ch.datascience.graph.elements

trait Vertex extends TypedMultiRecord {

  type Prop <: RichProperty

}

///**
//  * Created by johann on 27/04/17.
//  */
//trait Vertex[
//TypeId,
//Key,
//+Value,
//+MetaValue,
//+MetaProp <: Property[Key, MetaValue],
//+Prop <: RichProperty[Key, Value, MetaValue, MetaProp]
//] extends TypedMultiRecord[TypeId, Key, Value, Prop]