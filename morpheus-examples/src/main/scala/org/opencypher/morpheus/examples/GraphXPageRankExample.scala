/*
 * Copyright (c) 2016-2019 "Neo4j Sweden, AB" [https://neo4j.com]
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
 *
 * Attribution Notice under the terms of the Apache License 2.0
 *
 * This work was created by the collective efforts of the openCypher community.
 * Without limiting the terms of Section 6, any Derivative Work that is not
 * approved by the public consensus process of the openCypher Implementers Group
 * should not be described as “Cypher” (and Cypher® is a registered trademark of
 * Neo4j Inc.) or as "openCypher". Extensions by implementers or prototypes or
 * proposals for change that have been documented or implemented should only be
 * described as "implementation extensions to Cypher" or as "proposed changes to
 * Cypher that are not yet approved by the openCypher community".
 */
// tag::full-example[]
package org.opencypher.morpheus.examples

import org.apache.spark.graphx._
import org.opencypher.morpheus.api.MorpheusSession
import org.opencypher.morpheus.api.MorpheusSession._
import org.opencypher.morpheus.api.io.MorpheusElementTable
import org.opencypher.morpheus.impl.expressions.EncodeLong.decodeLong
import org.opencypher.morpheus.util.App
import org.opencypher.okapi.api.io.conversion.NodeMappingBuilder

/**
  * Round trip Morpheus -> GraphX -> Morpheus
  *
  * This example demonstrates how Morpheus results can be used to construct a GraphX graph and invoke a GraphX algorithm
  * on it. The computed ranks are imported back into Morpheus and used in a Cypher query.
  */
object GraphXPageRankExample extends App {

  // 1) Create Morpheus session
  implicit val morpheus: MorpheusSession = MorpheusSession.local()

  // 2) Load social network data via case class instances
  val socialNetwork = morpheus.readFrom(SocialNetworkData.persons, SocialNetworkData.friendships)

  // 3) Query graph with Cypher
  val nodes = socialNetwork.cypher(
    """|MATCH (n:Person)
       |RETURN id(n), n.name""".stripMargin)

  val rels = socialNetwork.cypher(
    """|MATCH (:Person)-[r]->(:Person)
       |RETURN startNode(r), endNode(r)
    """.stripMargin
  )

  // 4) Create GraphX compatible RDDs from nodes and relationships
  val graphXNodeRDD = nodes.records.asDataFrame.rdd.map(row => decodeLong(row.getAs[Array[Byte]](0)) -> row.getString(1))
  val graphXRelRDD = rels.records.asDataFrame.rdd.map(row => Edge(decodeLong(row.getAs[Array[Byte]](0)), decodeLong(row.getAs[Array[Byte]](1)), ()))

  // 5) Compute Page Rank via GraphX
  val graph = Graph(graphXNodeRDD, graphXRelRDD)
  val ranks = graph.pageRank(0.0001).vertices

  // 6) Convert RDD to DataFrame
  val rankTable = morpheus.sparkSession.createDataFrame(ranks)
    .withColumnRenamed("_1", "id")
    .withColumnRenamed("_2", "rank")

  // 7) Create property graph from rank data
  val ranksNodeMapping = NodeMappingBuilder.on("id").withPropertyKey("rank").build
  val rankNodes = morpheus.readFrom(MorpheusElementTable.create(ranksNodeMapping, rankTable))

  // 8) Mount both graphs in the session
  morpheus.catalog.store("ranks", rankNodes)
  morpheus.catalog.store("sn", socialNetwork)

  // 9) Query across both graphs to print names with corresponding ranks, sorted by rank
  val result = morpheus.cypher(
    """|FROM GRAPH ranks
       |MATCH (r)
       |WITH id(r) as id, r.rank as rank
       |FROM GRAPH sn
       |MATCH (p:Person)
       |WHERE id(p) = id
       |RETURN p.name as name, rank
       |ORDER BY rank DESC""".stripMargin)

  result.show
  //+---------------------------------------------+
  //| name                 | rank                 |
  //+---------------------------------------------+
  //| 'Carol'              | 1.4232365145228216   |
  //| 'Bob'                | 1.0235131396957122   |
  //| 'Alice'              | 0.5532503457814661   |
  //+---------------------------------------------+

}
// end::full-example[]