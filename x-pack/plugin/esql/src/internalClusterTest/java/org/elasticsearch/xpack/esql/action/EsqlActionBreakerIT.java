/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.action;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.compute.operator.exchange.ExchangeService;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.indices.breaker.HierarchyCircuitBreakerService;
import org.elasticsearch.plugins.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.transport.AbstractSimpleTransportTestCase.IGNORE_DESERIALIZATION_ERRORS_SETTING;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class EsqlActionBreakerIT extends EsqlActionIT {

    public static class InternalTransportSettingPlugin extends Plugin {
        @Override
        public List<Setting<?>> getSettings() {
            return List.of(IGNORE_DESERIALIZATION_ERRORS_SETTING);
        }
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        List<Class<? extends Plugin>> plugins = new ArrayList<>(super.nodePlugins());
        plugins.add(InternalExchangePlugin.class);
        plugins.add(InternalTransportSettingPlugin.class);
        return plugins;
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal, otherSettings))
            .put(HierarchyCircuitBreakerService.REQUEST_CIRCUIT_BREAKER_LIMIT_SETTING.getKey(), "128mb")
            /*
             * Force standard settings for the request breaker or we may not break at all.
             * Without this we can randomly decide to use the `noop` breaker for request
             * and it won't break.....
             */
            .put(
                HierarchyCircuitBreakerService.REQUEST_CIRCUIT_BREAKER_OVERHEAD_SETTING.getKey(),
                HierarchyCircuitBreakerService.REQUEST_CIRCUIT_BREAKER_OVERHEAD_SETTING.getDefault(Settings.EMPTY)
            )
            .put(
                HierarchyCircuitBreakerService.REQUEST_CIRCUIT_BREAKER_TYPE_SETTING.getKey(),
                HierarchyCircuitBreakerService.REQUEST_CIRCUIT_BREAKER_TYPE_SETTING.getDefault(Settings.EMPTY)
            )
            .put(ExchangeService.INACTIVE_SINKS_INTERVAL_SETTING, TimeValue.timeValueMillis(between(500, 2000)))
            // allow reading pages from network can trip the circuit breaker
            .put(IGNORE_DESERIALIZATION_ERRORS_SETTING.getKey(), true)
            .build();
    }

    private void setRequestCircuitBreakerLimit(ByteSizeValue limit) {
        if (limit != null) {
            assertAcked(
                clusterAdmin().prepareUpdateSettings()
                    .setPersistentSettings(
                        Settings.builder().put(HierarchyCircuitBreakerService.REQUEST_CIRCUIT_BREAKER_LIMIT_SETTING.getKey(), limit).build()
                    )
            );
        } else {
            assertAcked(
                clusterAdmin().prepareUpdateSettings()
                    .setPersistentSettings(
                        Settings.builder().putNull(HierarchyCircuitBreakerService.REQUEST_CIRCUIT_BREAKER_LIMIT_SETTING.getKey()).build()
                    )
            );
        }
    }

    @Override
    protected EsqlQueryResponse run(EsqlQueryRequest request) {
        setRequestCircuitBreakerLimit(ByteSizeValue.ofBytes(between(256, 2048)));
        try {
            return client().execute(EsqlQueryAction.INSTANCE, request).actionGet(2, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.info("request failed", e);
            ensureBlocksReleased();
        } finally {
            setRequestCircuitBreakerLimit(null);
        }
        return super.run(request);
    }

    /**
     * Makes sure that the circuit breaker is "plugged in" to ESQL by configuring an
     * unreasonably small breaker and tripping it.
     */
    public void testBreaker() {
        client().admin()
            .indices()
            .prepareCreate("test_breaker")
            .setMapping("foo", "type=keyword", "bar", "type=keyword")
            .setSettings(Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0).put("index.routing.rebalance.enable", "none"))
            .get();
        int numDocs = between(1000, 5000);
        for (int i = 0; i < numDocs; i++) {
            DocWriteResponse response = client().prepareIndex("test_breaker")
                .setId(Integer.toString(i))
                .setSource("foo", "foo-" + i, "bar", "bar-" + (i * 2))
                .get();
            assertThat(Strings.toString(response), response.getResult(), equalTo(DocWriteResponse.Result.CREATED));
        }
        client().admin().indices().prepareRefresh("test_breaker").get();
        ensureYellow("test_breaker");
        setRequestCircuitBreakerLimit(ByteSizeValue.ofBytes(between(256, 512)));
        try {
            final ElasticsearchException e = expectThrows(ElasticsearchException.class, () -> {
                var request = new EsqlQueryRequest();
                request.query("from test_breaker | stats count_distinct(foo) by bar");
                request.pragmas(randomPragmas());
                try (var ignored = client().execute(EsqlQueryAction.INSTANCE, request).actionGet(2, TimeUnit.MINUTES)) {

                }
            });
            logger.info("expected error", e);
            if (e instanceof CircuitBreakingException) {
                // The failure occurred before starting the drivers
                assertThat(e.getMessage(), containsString("Data too large"));
            } else {
                // The failure occurred after starting the drivers
                assertThat(e.getMessage(), containsString("Compute engine failure"));
                assertThat(e.getMessage(), containsString("Data too large"));
                assertThat(e.getCause(), instanceOf(CircuitBreakingException.class));
                assertThat(e.getCause().getMessage(), containsString("Data too large"));
            }
        } finally {
            setRequestCircuitBreakerLimit(null);
        }
    }
}
