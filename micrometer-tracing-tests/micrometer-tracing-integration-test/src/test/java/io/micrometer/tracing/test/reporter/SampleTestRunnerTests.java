/*
 * Copyright 2013-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micrometer.tracing.test.reporter;

import java.time.Duration;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.TimerRecordingHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.ipc.http.HttpSender;
import io.micrometer.core.ipc.http.HttpUrlConnectionSender;
import io.micrometer.core.util.internal.logging.InternalLogger;
import io.micrometer.core.util.internal.logging.InternalLoggerFactory;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingRecordingHandler;
import io.micrometer.tracing.reporter.wavefront.WavefrontSpanHandler;
import io.micrometer.tracing.test.SampleTestRunner;
import io.micrometer.tracing.test.reporter.wavefront.WavefrontAccessor;
import org.assertj.core.api.BDDAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.mockito.BDDMockito;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.BDDAssertions.then;

@Tag("docker")
class SampleTestRunnerTests extends SampleTestRunner {

    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(SampleTestRunnerTests.class);

    @Container
    private static final GenericContainer zipkin = new GenericContainer(DockerImageName.parse("openzipkin/zipkin"))
            .withExposedPorts(9411)
            .waitingFor(Wait.forHttp("/").forStatusCode(200));

    private static final Queue<String> traces = new LinkedList<>();

    @Override
    protected SampleRunnerConfig getSampleRunnerConfig() {
        return SampleRunnerConfig.builder()
                .wavefrontUrl("http://localhost:1234")
                .zipkinUrl("http://localhost:" + zipkin.getFirstMappedPort())
                .wavefrontToken("foo")
                .build();
    }

    WavefrontSpanHandler braveSpanHandler = WavefrontAccessor.setMockForBrave();

    WavefrontSpanHandler otelSpanHandler = WavefrontAccessor.setMockForOTel();

    @Override
    protected MeterRegistry getMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    Deque<TimerRecordingHandler> handlers;

    @Override
    public BiConsumer<BuildingBlocks, Deque<TimerRecordingHandler>> customizeTimerRecordingHandlers() {
        return (buildingBlocks, timerRecordingHandlers) -> {
            timerRecordingHandlers.addFirst(new MyRecordingHandler());
            this.handlers = timerRecordingHandlers;
        };
    }

    @BeforeAll
    static void setup() {
        zipkin.start();
    }

    @AfterAll
    static void cleanup() {
        zipkin.stop();
    }

    @AfterEach
    void assertions(TestInfo testInfo) throws Throwable {
        String testName = testInfo.getDisplayName().toLowerCase();
        String lastTrace = traces.remove();
        if (testName.contains("zipkin")) {
            assertThatZipkinRegisteredATrace(lastTrace);
        }
        else {
            WavefrontSpanHandler handler = testName.toLowerCase(Locale.ROOT).contains("brave") ? braveSpanHandler : otelSpanHandler;
            Awaitility.await()
                    .atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> BDDMockito.then(handler).should(BDDMockito.atLeastOnce()).end(BDDMockito.any(), BDDMockito.any()));
        }
        then(handlers.getFirst()).isInstanceOf(MyRecordingHandler.class);
        then(handlers.getLast()).isInstanceOf(DefaultTracingRecordingHandler.class);
    }

    @AfterEach
    void clean() {
        WavefrontAccessor.resetMocks();
    }

    private void assertThatZipkinRegisteredATrace(String lastTrace) throws Throwable {
        HttpSender httpSender = new HttpUrlConnectionSender();
        HttpSender.Response response = httpSender.get(getSampleRunnerConfig().zipkinUrl + "/api/v2/trace/" + lastTrace).send();

        BDDAssertions.then(response.isSuccessful()).isTrue();
        BDDAssertions.then(response.body()).isNotEmpty();
    }

    @Override
    public BiConsumer<Tracer, MeterRegistry> yourCode() {
        return (tracer, meterRegistry) -> {
            BDDAssertions.then(tracer.currentSpan()).isNotNull();
            traces.add(tracer.currentSpan().context().traceId());

            Timer.Sample start = Timer.start(meterRegistry);
            LOGGER.info("Hello");
            start.stop(Timer.builder("name"));
        };
    }

    static class MyRecordingHandler implements TimerRecordingHandler {

        @Override
        public void onStart(Timer.Sample sample, Timer.HandlerContext context) {
        }

        @Override
        public void onError(Timer.Sample sample, Timer.HandlerContext context, Throwable throwable) {
        }

        @Override
        public void onStop(Timer.Sample sample, Timer.HandlerContext context, Timer timer, Duration duration) {
        }

        @Override
        public boolean supportsContext(Timer.HandlerContext handlerContext) {
            return false;
        }
    }
}
