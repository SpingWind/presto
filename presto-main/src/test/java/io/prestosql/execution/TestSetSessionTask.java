/*
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
package io.prestosql.execution;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.metadata.Catalog;
import io.prestosql.metadata.CatalogManager;
import io.prestosql.metadata.Metadata;
import io.prestosql.security.AccessControl;
import io.prestosql.security.AllowAllAccessControl;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.resourcegroups.ResourceGroupId;
import io.prestosql.spi.session.PropertyMetadata;
import io.prestosql.sql.analyzer.FeaturesConfig;
import io.prestosql.sql.planner.FunctionCallBuilder;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.LongLiteral;
import io.prestosql.sql.tree.Parameter;
import io.prestosql.sql.tree.QualifiedName;
import io.prestosql.sql.tree.SetSession;
import io.prestosql.sql.tree.StringLiteral;
import io.prestosql.transaction.TransactionManager;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.spi.StandardErrorCode.INVALID_SESSION_PROPERTY;
import static io.prestosql.spi.session.PropertyMetadata.enumProperty;
import static io.prestosql.spi.session.PropertyMetadata.integerProperty;
import static io.prestosql.spi.session.PropertyMetadata.stringProperty;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.testing.TestingSession.createBogusTestingCatalog;
import static io.prestosql.transaction.InMemoryTransactionManager.createTestTransactionManager;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class TestSetSessionTask
{
    private static final String CATALOG_NAME = "foo";
    private static final String MUST_BE_POSITIVE = "property must be positive";

    private enum Size
    {
        SMALL,
        MEDIUM,
        LARGE,
    }

    private final TransactionManager transactionManager;
    private final AccessControl accessControl;
    private final Metadata metadata;

    public TestSetSessionTask()
    {
        CatalogManager catalogManager = new CatalogManager();
        transactionManager = createTestTransactionManager(catalogManager);
        accessControl = new AllowAllAccessControl();

        metadata = createTestMetadataManager(transactionManager, new FeaturesConfig());

        metadata.getSessionPropertyManager().addSystemSessionProperty(stringProperty(
                CATALOG_NAME,
                "test property",
                null,
                false));

        Catalog bogusTestingCatalog = createBogusTestingCatalog(CATALOG_NAME);

        List<PropertyMetadata<?>> sessionProperties = ImmutableList.of(
                stringProperty(
                        "bar",
                        "test property",
                        null,
                        false),
                integerProperty(
                        "positive_property",
                        "property that should be positive",
                        null,
                        TestSetSessionTask::validatePositive,
                        false),
                enumProperty(
                        "size_property",
                        "size enum property",
                        Size.class,
                        null,
                        false));

        metadata.getSessionPropertyManager().addConnectorSessionProperties(bogusTestingCatalog.getConnectorCatalogName(), sessionProperties);

        catalogManager.registerCatalog(bogusTestingCatalog);
    }

    private static void validatePositive(Object value)
    {
        int intValue = ((Number) value).intValue();
        if (intValue < 0) {
            throw new PrestoException(INVALID_SESSION_PROPERTY, MUST_BE_POSITIVE);
        }
    }

    private ExecutorService executor = newCachedThreadPool(daemonThreadsNamed(getClass().getSimpleName() + "-%s"));

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        executor.shutdownNow();
        executor = null;
    }

    @Test
    public void testSetSession()
    {
        testSetSession("bar", new StringLiteral("baz"), "baz");
        testSetSession("bar",
                new FunctionCallBuilder(metadata)
                        .setName(QualifiedName.of("concat"))
                        .addArgument(VARCHAR, new StringLiteral("ban"))
                        .addArgument(VARCHAR, new StringLiteral("ana"))
                        .build(),
                "banana");
    }

    @Test
    public void testSetSessionWithValidation()
    {
        testSetSession("positive_property", new LongLiteral("0"), "0");
        testSetSession("positive_property", new LongLiteral("2"), "2");

        try {
            testSetSession("positive_property", new LongLiteral("-1"), "-1");
            fail();
        }
        catch (PrestoException e) {
            assertEquals(e.getMessage(), MUST_BE_POSITIVE);
        }
    }

    @Test
    public void testSetSessionWithInvalidEnum()
    {
        try {
            testSetSession("size_property", new StringLiteral("XL"), "XL");
            fail();
        }
        catch (PrestoException e) {
            assertEquals(INVALID_SESSION_PROPERTY.toErrorCode(), e.getErrorCode());
            assertEquals("Invalid value [XL]. Valid values: [SMALL, MEDIUM, LARGE]", e.getMessage());
        }
    }

    @Test
    public void testSetSessionWithParameters()
    {
        FunctionCall functionCall = new FunctionCallBuilder(metadata)
                .setName(QualifiedName.of("concat"))
                .addArgument(VARCHAR, new StringLiteral("ban"))
                .addArgument(VARCHAR, new Parameter(0))
                .build();
        testSetSessionWithParameters("bar", functionCall, "banana", ImmutableList.of(new StringLiteral("ana")));
    }

    private void testSetSession(String property, Expression expression, String expectedValue)
    {
        testSetSessionWithParameters(property, expression, expectedValue, emptyList());
    }

    private void testSetSessionWithParameters(String property, Expression expression, String expectedValue, List<Expression> parameters)
    {
        QualifiedName qualifiedPropName = QualifiedName.of(CATALOG_NAME, property);
        QueryStateMachine stateMachine = QueryStateMachine.begin(
                format("set %s = 'old_value'", qualifiedPropName),
                Optional.empty(),
                TEST_SESSION,
                URI.create("fake://uri"),
                new ResourceGroupId("test"),
                false,
                transactionManager,
                accessControl,
                executor,
                metadata,
                WarningCollector.NOOP,
                Optional.empty());
        getFutureValue(new SetSessionTask().execute(new SetSession(qualifiedPropName, expression), transactionManager, metadata, accessControl, stateMachine, parameters));

        Map<String, String> sessionProperties = stateMachine.getSetSessionProperties();
        assertEquals(sessionProperties, ImmutableMap.of(qualifiedPropName.toString(), expectedValue));
    }
}
