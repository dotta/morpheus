/*
 * Copyright (c) 2016-2018 "Neo4j, Inc." [https://neo4j.com]
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
package org.opencypher.okapi.logical.impl.logical

import org.opencypher.okapi.api.graph.{GraphName, QualifiedGraphName}
import org.opencypher.okapi.api.io.PropertyGraphDataSource
import org.opencypher.okapi.api.schema.Schema
import org.opencypher.okapi.api.types._
import org.opencypher.okapi.api.value.CypherValue._
import org.opencypher.okapi.impl.io.SessionPropertyGraphDataSource
import org.opencypher.okapi.ir.api._
import org.opencypher.okapi.ir.api.block._
import org.opencypher.okapi.ir.api.expr._
import org.opencypher.okapi.ir.api.pattern.{CyclicRelationship, DirectedRelationship, Pattern}
import org.opencypher.okapi.ir.impl.util.VarConverters.{toVar => irFieldToVar}
import org.opencypher.okapi.ir.test.support.MatchHelper
import org.opencypher.okapi.ir.test.support.MatchHelper._
import org.opencypher.okapi.ir.test.{toField, toVar}
import org.opencypher.okapi.logical.LogicalTestSuite
import org.opencypher.okapi.logical.impl._
import org.scalatest.matchers._

import scala.language.implicitConversions

class LogicalPlannerTest extends LogicalTestSuite {
  val nodeA = IRField("a")(CTNode)
  val nodeB = IRField("b")(CTNode)
  val nodeG = IRField("g")(CTNode)
  val relR = IRField("r")(CTRelationship)


  val emptySqm = SolvedQueryModel.empty

  //  // Helper to create nicer expected results with `asCode`
  //  implicit val specialMappings = Map[Any, String](
  //    Schema.empty -> "Schema.empty",
  //    CTNode -> "CTNode",
  //    CTRelationship -> "CTRelationship",
  //    emptySqm -> "emptySqm",
  //    nodeA -> "nodeA",
  //    relR -> "relR",
  //    nodeB -> "nodeB",
  //    (nodeA: IRField) -> "nodeA",
  //    (relR: IRField) -> "relR",
  //    (nodeB: IRField) -> "nodeB"
  //  )

  test("convert load graph block") {
    val result = plan(irFor(leafBlock))
    val expected = Select(IndexedSeq.empty, Set.empty, leafPlan, emptySqm)
    result should equalWithTracing(expected)
  }

  test("convert match block") {
    val pattern = Pattern
      .empty[Expr]
      .withEntity(nodeA)
      .withEntity(nodeB)
      .withEntity(relR)
      .withConnection(relR, DirectedRelationship(nodeA, nodeB))

    val block = matchBlock(pattern)

    val scan1 = NodeScan(nodeA, leafPlan, emptySqm.withField(nodeA))
    val scan2 = NodeScan(nodeB, leafPlan, emptySqm.withField(nodeB))
    val ir = irWithLeaf(block)
    val result = plan(ir)

    val expected = Expand(nodeA, relR, nodeB, Directed, scan1, scan2, SolvedQueryModel(Set(nodeA, nodeB, relR)))

    result should equalWithoutResult(expected)
  }

  test("convert cyclic match block") {
    val pattern = Pattern
      .empty[Expr]
      .withEntity(nodeA)
      .withEntity(relR)
      .withConnection(relR, CyclicRelationship(nodeA))

    val block = matchBlock(pattern)
    val ir = irWithLeaf(block)

    val scan = NodeScan(nodeA, leafPlan, emptySqm.withField(nodeA))
    val expandInto = ExpandInto(nodeA, relR, nodeA, Directed, scan, SolvedQueryModel(Set(nodeA, relR)))

    plan(ir) should equalWithoutResult(expandInto)
  }

  test("convert project block") {
    val fields = Fields[Expr](Map(toField('a) -> Property('n, PropertyKey("prop"))(CTFloat)))
    val block = project(fields)

    val result = plan(irWithLeaf(block))

    val expected = Project(
      Property('n, PropertyKey("prop"))(CTFloat), // n is a dangling reference here
      Some('a),
      leafPlan,
      emptySqm.withFields('a))
    result should equalWithoutResult(expected)
  }

  test("plan query") {
    val ir = "MATCH (a:Administrator)-[r]->(g:Group) WHERE g.name = $foo RETURN a.name".irWithParams(
      "foo" -> CypherString("test"))
    val result = plan(ir)

    val expected = Project(
      Property(Var("a")(CTNode(Set("Administrator"))), PropertyKey("name"))(CTNull),
      Some(Var("a.name")(CTNull)),
      Filter(
        Equals(Property(Var("g")(CTNode(Set("Group"))), PropertyKey("name"))(CTNull), Param("foo")(CTString))(
          CTBoolean),
        Project(
          Property(Var("g")(CTNode(Set("Group"))), PropertyKey("name"))(CTNull),
          None,
          Filter(
            HasLabel(Var("g")(CTNode), Label("Group"))(CTBoolean),
            Filter(
              HasLabel(Var("a")(CTNode), Label("Administrator"))(CTBoolean),
              Expand(
                Var("a")(CTNode),
                Var("r")(CTRelationship),
                Var("g")(CTNode),
                Directed,
                NodeScan(
                  Var("a")(CTNode),
                  Start(LogicalCatalogGraph(testQualifiedGraphName, Schema.empty), Set(), emptySqm),
                  SolvedQueryModel(Set(nodeA), Set())
                ),
                NodeScan(
                  Var("g")(CTNode),
                  Start(LogicalCatalogGraph(testQualifiedGraphName, Schema.empty), Set(), emptySqm),
                  SolvedQueryModel(Set(IRField("g")(CTNode)), Set())),
                SolvedQueryModel(Set(nodeA, IRField("g")(CTNode), relR))
              ),
              SolvedQueryModel(
                Set(nodeA, IRField("g")(CTNode), relR),
                Set(HasLabel(Var("a")(CTNode), Label("Administrator"))(CTBoolean)))
            ),
            SolvedQueryModel(
              Set(nodeA, IRField("g")(CTNode), relR),
              Set(
                HasLabel(Var("a")(CTNode), Label("Administrator"))(CTBoolean),
                HasLabel(Var("g")(CTNode), Label("Group"))(CTBoolean))
            )
          ),
          SolvedQueryModel(
            Set(nodeA, IRField("g")(CTNode), relR),
            Set(
              HasLabel(Var("a")(CTNode), Label("Administrator"))(CTBoolean),
              HasLabel(Var("g")(CTNode), Label("Group"))(CTBoolean))
          )
        ),
        SolvedQueryModel(
          Set(nodeA, IRField("g")(CTNode), relR),
          Set(
            HasLabel(Var("a")(CTNode), Label("Administrator"))(CTBoolean),
            HasLabel(Var("g")(CTNode), Label("Group"))(CTBoolean),
            Equals(Property(Var("g")(CTNode(Set("Group"))), PropertyKey("name"))(CTNull), Param("foo")(CTString))(
              CTBoolean)
          )
        )
      ),
      SolvedQueryModel(
        Set(nodeA, IRField("g")(CTNode), relR, IRField("a.name")(CTNull)),
        Set(
          HasLabel(Var("a")(CTNode), Label("Administrator"))(CTBoolean),
          HasLabel(Var("g")(CTNode), Label("Group"))(CTBoolean),
          Equals(Property(Var("g")(CTNode(Set("Group"))), PropertyKey("name"))(CTNull), Param("foo")(CTString))(
            CTBoolean)
        )
      )
    )
    result should equalWithoutResult(expected)
  }

  test("plan query with type information") {
    implicit val schema: Schema = Schema.empty
      .withNodePropertyKeys("Group")("name" -> CTString)
      .withNodePropertyKeys("Administrator")("name" -> CTFloat)

    val ir = "MATCH (a:Administrator)-[r]->(g:Group) WHERE g.name = $foo RETURN a.name".irWithParams(
      "foo" -> CypherString("test"))

    val result = plan(ir, schema)

    val expected = Project(
      Property(Var("a")(CTNode(Set("Administrator"))), PropertyKey("name"))(CTFloat),
      Some(Var("a.name")(CTFloat)),
      Filter(
        Equals(Property(Var("g")(CTNode(Set("Group"))), PropertyKey("name"))(CTString), Param("foo")(CTString))(
          CTBoolean),
        Project(
          Property(Var("g")(CTNode(Set("Group"))), PropertyKey("name"))(CTString),
          None,
          Filter(
            HasLabel(Var("g")(CTNode), Label("Group"))(CTBoolean),
            Filter(
              HasLabel(Var("a")(CTNode), Label("Administrator"))(CTBoolean),
              Expand(
                Var("a")(CTNode),
                Var("r")(CTRelationship),
                Var("g")(CTNode),
                Directed,
                NodeScan(
                  Var("a")(CTNode),
                  Start(
                    LogicalCatalogGraph(
                      testQualifiedGraphName,
                      schema
                    ),
                    Set(),
                    emptySqm
                  ),
                  SolvedQueryModel(Set(nodeA), Set())
                ),
                NodeScan(
                  Var("g")(CTNode),
                  Start(
                    LogicalCatalogGraph(
                      testQualifiedGraphName,
                      schema
                    ),
                    Set(),
                    emptySqm
                  ),
                  SolvedQueryModel(Set(IRField("g")(CTNode)), Set())
                ),
                SolvedQueryModel(Set(nodeA, IRField("g")(CTNode), relR), Set())
              ),
              SolvedQueryModel(
                Set(nodeA, IRField("g")(CTNode), relR),
                Set(HasLabel(Var("a")(CTNode), Label("Administrator"))(CTBoolean)))
            ),
            SolvedQueryModel(
              Set(nodeA, IRField("g")(CTNode), relR),
              Set(
                HasLabel(Var("a")(CTNode), Label("Administrator"))(CTBoolean),
                HasLabel(Var("g")(CTNode), Label("Group"))(CTBoolean))
            )
          ),
          SolvedQueryModel(
            Set(nodeA, IRField("g")(CTNode), relR),
            Set(
              HasLabel(Var("a")(CTNode), Label("Administrator"))(CTBoolean),
              HasLabel(Var("g")(CTNode), Label("Group"))(CTBoolean))
          )
        ),
        SolvedQueryModel(
          Set(nodeA, IRField("g")(CTNode), relR),
          Set(
            HasLabel(Var("a")(CTNode), Label("Administrator"))(CTBoolean),
            HasLabel(Var("g")(CTNode), Label("Group"))(CTBoolean),
            Equals(Property(Var("g")(CTNode(Set("Group"))), PropertyKey("name"))(CTString), Param("foo")(CTString))(
              CTBoolean)
          )
        )
      ),
      SolvedQueryModel(
        Set(nodeA, IRField("g")(CTNode), relR, IRField("a.name")(CTFloat)),
        Set(
          HasLabel(Var("a")(CTNode), Label("Administrator"))(CTBoolean),
          HasLabel(Var("g")(CTNode), Label("Group"))(CTBoolean),
          Equals(Property(Var("g")(CTNode(Set("Group"))), PropertyKey("name"))(CTString), Param("foo")(CTString))(
            CTBoolean)
        )
      )
    )

    result should equalWithoutResult(expected)
  }

  test("plan query with negation") {
    val ir =
      "MATCH (a) WHERE NOT $p1 = $p2 RETURN a.prop".irWithParams("p1" -> CypherInteger(1L), "p2" -> CypherBoolean(true))

    val result = plan(ir)

    val expected = Project(
      Property(Var("a")(CTNode), PropertyKey("prop"))(CTNull),
      Some(Var("a.prop")(CTNull)),
      Filter(
        Not(Equals(Param("p1")(CTInteger), Param("p2")(CTBoolean))(CTBoolean))(CTBoolean),
        NodeScan(
          Var("a")(CTNode),
          Start(LogicalCatalogGraph(testQualifiedGraphName, Schema.empty), Set(), emptySqm),
          SolvedQueryModel(Set(nodeA), Set())
        ),
        SolvedQueryModel(
          Set(nodeA),
          Set(Not(Equals(Param("p1")(CTInteger), Param("p2")(CTBoolean))(CTBoolean))(CTBoolean)))
      ),
      SolvedQueryModel(
        Set(nodeA, IRField("a.prop")(CTNull)),
        Set(Not(Equals(Param("p1")(CTInteger), Param("p2")(CTBoolean))(CTBoolean))(CTBoolean)))
    )

    result should equalWithoutResult(expected)
  }

  private val planner = new LogicalPlanner(new LogicalOperatorProducer)

  private def plan(ir: CypherQuery[Expr], schema: Schema = Schema.empty): LogicalOperator =
    plan(ir, schema, testGraphName -> schema)

  private def plan(ir: CypherQuery[Expr], ambientSchema: Schema, graphWithSchema: (GraphName, Schema)*): LogicalOperator = {
    val withAmbientGraph = graphWithSchema :+ (testGraphName -> ambientSchema)
    planner.process(ir)(LogicalPlannerContext(ambientSchema, Set.empty, Map(testQualifiedGraphName -> graphSource(withAmbientGraph: _*))))
  }

  case class equalWithoutResult(plan: LogicalOperator) extends Matcher[LogicalOperator] {
    override def apply(left: LogicalOperator): MatchResult = {
      left match {
        case Select(_, _, in, _) =>
          val planMatch = equalWithTracing(in)(plan)
          val solvedMatch = equalWithTracing(in.solved)(plan.solved)
          MatchHelper.combine(planMatch, solvedMatch)
        case _ => MatchResult(matches = false, "Expected a Select plan on top", "")
      }
    }
  }

  def graphSource(graphWithSchema: (GraphName, Schema)*): PropertyGraphDataSource = {
    import org.mockito.Mockito.when

    val graphSource = mock[PropertyGraphDataSource]
    graphWithSchema.foreach {
      case (graphName, schema) => when(graphSource.schema(graphName)).thenReturn(Some(schema))
    }

    graphSource
  }
}
