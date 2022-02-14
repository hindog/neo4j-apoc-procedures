package apoc.schema;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.schema.ConstraintDefinition;
import org.neo4j.graphdb.schema.ConstraintType;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.graphdb.schema.Schema;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static apoc.util.TestUtil.*;
import static java.util.Arrays.asList;
import static org.junit.Assert.*;
import static org.neo4j.configuration.SettingImpl.newBuilder;
import static org.neo4j.configuration.SettingValueParsers.BOOL;

/**
 * @author mh
 * @since 12.05.16
 */
public class SchemasTest {

    @Rule
    public DbmsRule db = new ImpermanentDbmsRule()
            .withSetting(GraphDatabaseSettings.procedure_unrestricted, Collections.singletonList("apoc.*"));

    private static void accept(Result result) {
        Map<String, Object> r = result.next();

        assertEquals(":Foo(bar)", r.get("name"));
        assertEquals("ONLINE", r.get("status"));
        assertEquals("Foo", r.get("label"));
        assertEquals("INDEX", r.get("type"));
        assertEquals("bar", ((List<String>) r.get("properties")).get(0));
        assertEquals("NO FAILURE", r.get("failure"));
        assertEquals(100d, r.get("populationProgress"));
        assertEquals(1d, r.get("valuesSelectivity"));
        Assertions.assertThat( r.get( "userDescription").toString() ).contains("name='index1', type='GENERAL RANGE', schema=(:Foo {bar}), indexProvider='range-1.0' )");

        assertTrue(!result.hasNext());
    }

    private static void accept2(Result result) {
        Map<String, Object> r = result.next();

        assertEquals(":Foo(bar)", r.get("name"));
        assertEquals("ONLINE", r.get("status"));
        assertEquals("Foo", r.get("label"));
        assertEquals("INDEX", r.get("type"));
        assertEquals("bar", ((List<String>) r.get("properties")).get(0));
        assertEquals("NO FAILURE", r.get("failure"));
        assertEquals(100d, r.get("populationProgress"));
        assertEquals(1d, r.get("valuesSelectivity"));
        Assertions.assertThat( r.get( "userDescription").toString() ).contains( "name='index1', type='GENERAL RANGE', schema=(:Foo {bar}), indexProvider='range-1.0' )" );

        r = result.next();

        assertEquals(":Person(name)", r.get("name"));
        assertEquals("ONLINE", r.get("status"));
        assertEquals("Person", r.get("label"));
        assertEquals("INDEX", r.get("type"));
        assertEquals("name", ((List<String>) r.get("properties")).get(0));
        assertEquals("NO FAILURE", r.get("failure"));
        assertEquals(100d, r.get("populationProgress"));
        assertEquals(1d, r.get("valuesSelectivity"));
        Assertions.assertThat( r.get( "userDescription").toString() ).contains( "name='index3', type='GENERAL TEXT', schema=(:Person {name}), indexProvider='text-1.0' )" );

        assertTrue(!result.hasNext());
    }

    @Before
    public void setUp() throws Exception {
        registerProcedure(db, Schemas.class);
    }

    @Test
    public void testCreateIndex() throws Exception {
        dropSchema();
        testCall(db, "CALL apoc.schema.assert({Foo:['bar']},null)", (r) -> {
            assertEquals("Foo", r.get("label"));
            assertEquals("bar", r.get("key"));
            assertEquals(false, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<IndexDefinition> indexes = Iterables.asList(tx.schema().getIndexes(Label.label("Foo")));
            assertEquals(1, indexes.size());
            assertEquals("Foo", Iterables.single(indexes.get(0).getLabels()).name());
            assertEquals(asList("bar"), indexes.get(0).getPropertyKeys());
        }
    }

    @Test
    public void testCreateSchema() throws Exception {
        testCall(db, "CALL apoc.schema.assert(null,{Foo:['bar']})", (r) -> {
            assertEquals("Foo", r.get("label"));
            assertEquals("bar", r.get("key"));
            assertEquals(true, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<ConstraintDefinition> constraints = Iterables.asList(tx.schema().getConstraints(Label.label("Foo")));
            assertEquals(1, constraints.size());
            ConstraintDefinition constraint = constraints.get(0);
            assertEquals(ConstraintType.UNIQUENESS, constraint.getConstraintType());
            assertEquals("Foo", constraint.getLabel().name());
            assertEquals("bar", Iterables.single(constraint.getPropertyKeys()));
        }
    }

    @Test
    public void testDropIndexWhenUsingDropExisting() throws Exception {
        dropSchema();
        db.executeTransactionally("CREATE INDEX FOR (n:Foo) ON (n.bar)");
        testCall(db, "CALL apoc.schema.assert(null,null)", (r) -> {
            assertEquals("Foo", r.get("label"));
            assertEquals("bar", r.get("key"));
            assertEquals(false, r.get("unique"));
            assertEquals("DROPPED", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<IndexDefinition> indexes = Iterables.asList(tx.schema().getIndexes());
            assertEquals(0, indexes.size());
        }
    }

    @Test
    public void testDropIndexAndCreateIndexWhenUsingDropExisting() throws Exception {
        dropSchema();
        db.executeTransactionally("CREATE INDEX FOR (n:Foo) ON (n.bar)");
        testResult(db, "CALL apoc.schema.assert({Bar:['foo']},null)", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals("bar", r.get("key"));
            assertEquals(false, r.get("unique"));
            assertEquals("DROPPED", r.get("action"));

            r = result.next();
            assertEquals("Bar", r.get("label"));
            assertEquals("foo", r.get("key"));
            assertEquals(false, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<IndexDefinition> indexes = Iterables.asList(tx.schema().getIndexes());
            assertEquals(1, indexes.size());
        }
    }

    @Test
    public void testRetainIndexWhenNotUsingDropExisting() throws Exception {
        dropSchema();
        db.executeTransactionally("CREATE INDEX FOR (n:Foo) ON (n.bar)");
        testResult(db, "CALL apoc.schema.assert({Bar:['foo', 'bar']}, null, false)", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals("bar", r.get("key"));
            assertEquals(false, r.get("unique"));
            assertEquals("KEPT", r.get("action"));

            r = result.next();
            assertEquals("Bar", r.get("label"));
            assertEquals("foo", r.get("key"));
            assertEquals(false, r.get("unique"));
            assertEquals("CREATED", r.get("action"));

            r = result.next();
            assertEquals("Bar", r.get("label"));
            assertEquals("bar", r.get("key"));
            assertEquals(false, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<IndexDefinition> indexes = Iterables.asList(tx.schema().getIndexes());
            assertEquals(3, indexes.size());
        }
    }

    @Test
    public void testDropSchemaWhenUsingDropExisting() throws Exception {
        db.executeTransactionally("CREATE CONSTRAINT FOR (f:Foo) REQUIRE f.bar IS UNIQUE");
        testCall(db, "CALL apoc.schema.assert(null,null)", (r) -> {
            assertEquals("Foo", r.get("label"));
            assertEquals("bar", r.get("key"));
            assertEquals(true, r.get("unique"));
            assertEquals("DROPPED", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<ConstraintDefinition> constraints = Iterables.asList(tx.schema().getConstraints());
            assertEquals(0, constraints.size());
        }
    }

    @Test
    public void testDropSchemaAndCreateSchemaWhenUsingDropExisting() throws Exception {
        db.executeTransactionally("CREATE CONSTRAINT FOR (f:Foo) REQUIRE f.bar IS UNIQUE");
        testResult(db, "CALL apoc.schema.assert(null, {Bar:['foo']})", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals("bar", r.get("key"));
            assertEquals(true, r.get("unique"));
            assertEquals("DROPPED", r.get("action"));

            r = result.next();
            assertEquals("Bar", r.get("label"));
            assertEquals("foo", r.get("key"));
            assertEquals(true, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<ConstraintDefinition> constraints = Iterables.asList(tx.schema().getConstraints());
            assertEquals(1, constraints.size());
        }
    }

    @Test
    public void testRetainSchemaWhenNotUsingDropExisting() throws Exception {
        db.executeTransactionally("CREATE CONSTRAINT FOR (f:Foo) REQUIRE f.bar IS UNIQUE");
        testResult(db, "CALL apoc.schema.assert(null, {Bar:['foo', 'bar']}, false)", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals("bar", r.get("key"));
            assertEquals(true, r.get("unique"));
            assertEquals("KEPT", r.get("action"));

            r = result.next();
            assertEquals("Bar", r.get("label"));
            assertEquals("foo", r.get("key"));
            assertEquals(true, r.get("unique"));
            assertEquals("CREATED", r.get("action"));

            r = result.next();
            assertEquals("Bar", r.get("label"));
            assertEquals("bar", r.get("key"));
            assertEquals(true, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<ConstraintDefinition> constraints = Iterables.asList(tx.schema().getConstraints());
            assertEquals(3, constraints.size());
        }
    }

    @Test
    public void testKeepIndex() throws Exception {
        dropSchema();
        db.executeTransactionally("CREATE INDEX FOR (n:Foo) ON (n.bar)");
        testResult(db, "CALL apoc.schema.assert({Foo:['bar', 'foo']},null,false)", (result) -> { 
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals("bar", r.get("key"));
            assertEquals(false, r.get("unique"));
            assertEquals("KEPT", r.get("action"));

            r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals("foo", r.get("key"));
            assertEquals(false, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<IndexDefinition> indexes = Iterables.asList(tx.schema().getIndexes());
            assertEquals(2, indexes.size());
        }
    }

    @Test
    public void testKeepSchema() throws Exception {
        db.executeTransactionally("CREATE CONSTRAINT FOR (f:Foo) REQUIRE f.bar IS UNIQUE");
        testResult(db, "CALL apoc.schema.assert(null,{Foo:['bar', 'foo']})", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals("bar", r.get("key"));
            assertEquals(expectedKeys("bar"), r.get("keys"));
            assertEquals(true, r.get("unique"));
            assertEquals("KEPT", r.get("action"));

            r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals("foo", r.get("key"));
            assertEquals(true, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<ConstraintDefinition> constraints = Iterables.asList(tx.schema().getConstraints());
            assertEquals(2, constraints.size());
        }
    }

    @Test
    public void testIndexes() {
        db.executeTransactionally("CREATE RANGE INDEX index1 FOR (n:Foo) ON (n.bar)");
        awaitIndexesOnline();
        testResult(db, "CALL apoc.schema.nodes()", (result) -> {
            // Get the index info
            Map<String, Object> r = result.next();

            assertEquals(":Foo(bar)", r.get("name"));
            assertEquals("ONLINE", r.get("status"));
            assertEquals("Foo", r.get("label"));
            assertEquals("INDEX", r.get("type"));
            assertEquals("bar", ((List<String>) r.get("properties")).get(0));
            assertEquals("NO FAILURE", r.get("failure"));
            assertEquals(100d, r.get("populationProgress"));
            assertEquals(1d, r.get("valuesSelectivity"));
            Assertions.assertThat(r.get("userDescription").toString()).contains("name='index1', type='GENERAL RANGE', schema=(:Foo {bar}), indexProvider='range-1.0' )");

            assertFalse(result.hasNext());
        });
    }

    @Test
    public void testIndexExists() {
        db.executeTransactionally("CREATE INDEX FOR (n:Foo) ON (n.bar)");
        testResult(db, "RETURN apoc.schema.node.indexExists('Foo', ['bar'])", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals(true, r.entrySet().iterator().next().getValue());
        });
    }

    @Test
    public void testIndexNotExists() {
        db.executeTransactionally("CREATE INDEX FOR (n:Foo) ON (n.bar)");
        testResult(db, "RETURN apoc.schema.node.indexExists('Bar', ['foo'])", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals(false, r.entrySet().iterator().next().getValue());
        });
    }



    @Test
    public void testUniquenessConstraintOnNode() {
        db.executeTransactionally("CREATE CONSTRAINT FOR (bar:Bar) REQUIRE bar.foo IS UNIQUE");
        awaitIndexesOnline();

        testResult(db, "CALL apoc.schema.nodes()", (result) -> {
            Map<String, Object> r = result.next();

            assertEquals(":Bar(foo)", r.get("name"));
            assertEquals("Bar", r.get("label"));
            assertEquals("UNIQUENESS", r.get("type"));
            assertEquals("foo", ((List<String>) r.get("properties")).get(0));

            assertFalse(result.hasNext());
        });
    }

    @Test
    public void testIndexAndUniquenessConstraintOnNode() {
        db.executeTransactionally("CREATE INDEX FOR (n:Foo) ON (n.foo)");
        db.executeTransactionally("CREATE CONSTRAINT FOR (bar:Bar) REQUIRE bar.bar IS UNIQUE");
        awaitIndexesOnline();

        testResult(db, "CALL apoc.schema.nodes()", (result) -> {
            Map<String, Object> r = result.next();

            assertEquals("Bar", r.get("label"));
            assertEquals("UNIQUENESS", r.get("type"));
            assertEquals("bar", ((List<String>) r.get("properties")).get(0));

            r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals("INDEX", r.get("type"));
            assertEquals("foo", ((List<String>) r.get("properties")).get(0));
            assertEquals("ONLINE", r.get("status"));

            assertFalse(result.hasNext());
        });
    }

    @Test
    public void testDropCompoundIndexWhenUsingDropExisting() throws Exception {
        dropSchema();
        db.executeTransactionally("CREATE INDEX FOR (n:Foo) ON (n.bar,n.baa)");
        awaitIndexesOnline();
        testResult(db, "CALL apoc.schema.assert(null,null,true)", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("bar", "baa"), r.get("keys"));
            assertEquals(false, r.get("unique"));
            assertEquals("DROPPED", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<IndexDefinition> indexes = Iterables.asList(tx.schema().getIndexes());
            assertEquals(0, indexes.size());
        }
    }

    @Test
    public void testDropCompoundIndexAndRecreateWithDropExisting() throws Exception {
        dropSchema();
        db.executeTransactionally("CREATE INDEX FOR (n:Foo) ON (n.bar,n.baa)");
        awaitIndexesOnline();
        testResult(db, "CALL apoc.schema.assert({Foo:[['bar','baa']]},null,true)", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("bar", "baa"), r.get("keys"));
            assertEquals(false, r.get("unique"));
            assertEquals("DROPPED", r.get("action"));
            result.close();
        });
        try (Transaction tx = db.beginTx()) {
            List<IndexDefinition> indexes = Iterables.asList(tx.schema().getIndexes());
            assertEquals(1, indexes.size());
        }
    }

    @Test
    public void testDoesntDropCompoundIndexWhenSupplyingSameCompoundIndex() throws Exception {
        dropSchema();
        db.executeTransactionally("CREATE INDEX FOR (n:Foo) ON (n.bar,n.baa)");
        awaitIndexesOnline();
        testResult(db, "CALL apoc.schema.assert({Foo:[['bar','baa']]},null,false)", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("bar", "baa"), r.get("keys"));
            assertEquals(false, r.get("unique"));
            assertEquals("KEPT", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<IndexDefinition> indexes = Iterables.asList(tx.schema().getIndexes());
            assertEquals(1, indexes.size());
        }
    }
    /*
        This is only for 3.2+
    */
    @Test
    public void testKeepCompoundIndex() throws Exception {
        dropSchema();
        db.executeTransactionally("CREATE INDEX FOR (n:Foo) ON (n.bar,n.baa)");
        awaitIndexesOnline();
        testResult(db, "CALL apoc.schema.assert({Foo:[['bar','baa'], ['foo','faa']]},null,false)", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("bar", "baa"), r.get("keys"));
            assertEquals(false, r.get("unique"));
            assertEquals("KEPT", r.get("action"));

            r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("foo", "faa"), r.get("keys"));
            assertEquals(false, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<IndexDefinition> indexes = Iterables.asList(tx.schema().getIndexes());
            assertEquals(2, indexes.size());
        }
    }

    @Test
    public void testDropIndexAndCreateCompoundIndexWhenUsingDropExisting() throws Exception {
        dropSchema();
        db.executeTransactionally("CREATE INDEX FOR (n:Foo) ON (n.bar)");
        awaitIndexesOnline();
        testResult(db, "CALL apoc.schema.assert({Bar:[['foo','bar']]},null)", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals("bar", r.get("key"));
            assertEquals(false, r.get("unique"));
            assertEquals("DROPPED", r.get("action"));

            r = result.next();
            assertEquals("Bar", r.get("label"));
            assertEquals(expectedKeys("foo", "bar"), r.get("keys"));
            assertEquals(false, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<IndexDefinition> indexes = Iterables.asList(tx.schema().getIndexes());
            assertEquals(1, indexes.size());
        }
    }

    @Test
    public void testDropCompoundIndexAndCreateCompoundIndexWhenUsingDropExisting() throws Exception {
        dropSchema();
        db.executeTransactionally("CREATE INDEX FOR (n:Foo) ON (n.bar,n.baa)");
        awaitIndexesOnline();
        testResult(db, "CALL apoc.schema.assert({Foo:[['bar','baa']]},null)", (result) -> {
            Map<String, Object> r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("bar","baa"), r.get("keys"));
            assertEquals(false, r.get("unique"));
            assertEquals("DROPPED", r.get("action"));

            r = result.next();
            assertEquals("Foo", r.get("label"));
            assertEquals(expectedKeys("bar", "baa"), r.get("keys"));
            assertEquals(false, r.get("unique"));
            assertEquals("CREATED", r.get("action"));
        });
        try (Transaction tx = db.beginTx()) {
            List<IndexDefinition> indexes = Iterables.asList(tx.schema().getIndexes());
            assertEquals(1, indexes.size());
        }
    }

    private List<String> expectedKeys(String... keys){
        return asList(keys);
    }


    @Test
    public void testIndexesOneLabel() {
        db.executeTransactionally("CREATE RANGE INDEX index1 FOR (n:Foo) ON (n.bar)");
        db.executeTransactionally("CREATE RANGE INDEX index2 FOR (n:Bar) ON (n.foo)");
        db.executeTransactionally("CREATE TEXT INDEX index3 FOR (n:Person) ON (n.name)");
        db.executeTransactionally("CREATE TEXT INDEX index4 FOR (n:Movie) ON (n.title)");
        awaitIndexesOnline();
        testResult(db, "CALL apoc.schema.nodes({labels:['Foo']})", // Get the index info
                SchemasTest::accept);
    }

    private void awaitIndexesOnline() {
        try (Transaction tx = db.beginTx()) {
            tx.schema().awaitIndexesOnline(5, TimeUnit.SECONDS);
            tx.commit();
        }
    }

    @Test
    public void testIndexesMoreLabels() {
        db.executeTransactionally("CREATE RANGE INDEX index1 FOR (n:Foo) ON (n.bar)");
        db.executeTransactionally("CREATE RANGE INDEX index2 FOR (n:Bar) ON (n.foo)");
        db.executeTransactionally("CREATE TEXT INDEX index3 FOR (n:Person) ON (n.name)");
        db.executeTransactionally("CREATE TEXT INDEX index4 FOR (n:Movie) ON (n.title)");
        awaitIndexesOnline();
        testResult(db, "CALL apoc.schema.nodes({labels:['Foo', 'Person']})", // Get the index info
                SchemasTest::accept2);
    }

    @Test
    public void testSchemaRelationshipsExclude() {
        ignoreException(() -> {
            db.executeTransactionally("CREATE CONSTRAINT FOR ()-[like:LIKED]-() REQUIRE exists(like.day)");
            testResult(db, "CALL apoc.schema.relationships({excludeRelationships:['LIKED']})", (result) -> assertFalse(result.hasNext()));
        }, QueryExecutionException.class);
    }

    @Test
    public void testSchemaNodesExclude() {
        ignoreException(() -> {
            db.executeTransactionally("CREATE CONSTRAINT FOR (book:Book) REQUIRE book.isbn IS UNIQUE");
            testResult(db, "CALL apoc.schema.nodes({excludeLabels:['Book']})", (result) -> assertFalse(result.hasNext()));

        }, QueryExecutionException.class);
    }

    @Test(expected = QueryExecutionException.class)
    public void testIndexesLabelsAndExcludeLabelsValuatedShouldFail() {
        db.executeTransactionally("CREATE INDEX FOR (n:Foo) ON (n.bar)");
        db.executeTransactionally("CREATE INDEX FOR (n:Bar) ON (n.foo)");
        db.executeTransactionally("CREATE INDEX FOR (n:Person) ON (n.name)");
        db.executeTransactionally("CREATE INDEX FOR (n:Movie) ON (n.title)");
        awaitIndexesOnline();
        try (Transaction tx = db.beginTx()) {
            tx.schema().awaitIndexesOnline(5, TimeUnit.SECONDS);
            tx.commit();
            testResult(db, "CALL apoc.schema.nodes({labels:['Foo', 'Person', 'Bar'], excludeLabels:['Bar']})", (result) -> {});
        } catch (IllegalArgumentException e) {
            Throwable except = ExceptionUtils.getRootCause(e);
            assertTrue(except instanceof IllegalArgumentException);
            assertEquals("Parameters labels and excludelabels are both valuated. Please check parameters and valuate only one.", except.getMessage());
            throw e;
        }

    }

    @Test(expected = QueryExecutionException.class)
    public void testConstraintsRelationshipsAndExcludeRelationshipsValuatedShouldFail() {
        db.executeTransactionally("CREATE CONSTRAINT FOR ()-[like:LIKED]-() REQUIRE exists(like.day)");
        db.executeTransactionally("CREATE CONSTRAINT FOR ()-[knows:SINCE]-() REQUIRE exists(since.year)");
        awaitIndexesOnline();
        try (Transaction tx = db.beginTx()) {
            tx.schema().awaitIndexesOnline(5, TimeUnit.SECONDS);
            tx.commit();
            testResult(db, "CALL apoc.schema.relationships({relationships:['LIKED'], excludeRelationships:['SINCE']})", (result) -> {});
        } catch (IllegalArgumentException e) {
            Throwable except = ExceptionUtils.getRootCause(e);
            assertTrue(except instanceof IllegalArgumentException);
            assertEquals("Parameters relationships and excluderelationships are both valuated. Please check parameters and valuate only one.", except.getMessage());
            throw e;
        }
    }

    private void dropSchema()
    {
        try(Transaction tx = db.beginTx()) {
            Schema schema = tx.schema();
            schema.getConstraints().forEach(ConstraintDefinition::drop);
            schema.getIndexes().forEach(IndexDefinition::drop);
            tx.commit();
        }
    }
}
