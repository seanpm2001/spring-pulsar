/*
 * Copyright 2023 the original author or authors.
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

package org.springframework.pulsar.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.junit.jupiter.api.Test;

import org.springframework.core.log.LogAccessor;
import org.springframework.pulsar.listener.DefaultPulsarMessageListenerContainer;
import org.springframework.pulsar.listener.PulsarContainerProperties;
import org.springframework.pulsar.listener.PulsarMessageListenerContainer;
import org.springframework.pulsar.listener.PulsarRecordMessageListener;
import org.springframework.pulsar.test.support.PulsarTestContainerSupport;

/**
 * Tests for shared subscription types in Pulsar consumer.
 *
 * @author Soby Chacko
 */
public class SharedSubscriptionConsumerTests implements PulsarTestContainerSupport {

	private final LogAccessor logger = new LogAccessor(this.getClass());

	@Test
	void sharedSubscriptionRoundRobinBasicScenario() throws Exception {

		DefaultPulsarMessageListenerContainer<String> container1 = null;
		DefaultPulsarMessageListenerContainer<String> container2 = null;
		DefaultPulsarMessageListenerContainer<String> container3 = null;
		PulsarClient pulsarClient = null;
		try {
			pulsarClient = PulsarClient.builder().serviceUrl(PulsarTestContainerSupport.getPulsarBrokerUrl()).build();
			DefaultPulsarConsumerFactory<String> pulsarConsumerFactory = new DefaultPulsarConsumerFactory<>(
					pulsarClient,
					Map.of("topicNames", Collections.singleton("shared-subscription-single-msg-test-topic"),
							"subscriptionName", "shared-subscription-single-msg-test-sub"));

			CountDownLatch latch1 = new CountDownLatch(1);
			CountDownLatch latch2 = new CountDownLatch(1);
			CountDownLatch latch3 = new CountDownLatch(1);

			container1 = createAndStartContainerForShared(latch1, "hello john doe", pulsarConsumerFactory);
			container2 = createAndStartContainerForShared(latch2, "hello alice doe", pulsarConsumerFactory);
			container3 = createAndStartContainerForShared(latch3, "hello buzz doe", pulsarConsumerFactory);

			Map<String, Object> prodConfig = Map.of("topicName", "shared-subscription-single-msg-test-topic");
			DefaultPulsarProducerFactory<String> pulsarProducerFactory = new DefaultPulsarProducerFactory<>(
					pulsarClient, prodConfig);
			PulsarTemplate<String> pulsarTemplate = new PulsarTemplate<>(pulsarProducerFactory);

			pulsarTemplate.newMessage("hello john doe").sendAsync();
			pulsarTemplate.newMessage("hello alice doe").sendAsync();
			pulsarTemplate.newMessage("hello buzz doe").sendAsync();

			boolean await1 = latch1.await(10, TimeUnit.SECONDS);
			boolean await2 = latch2.await(10, TimeUnit.SECONDS);
			boolean await3 = latch3.await(10, TimeUnit.SECONDS);
			assertThat(await1).isTrue();
			assertThat(await2).isTrue();
			assertThat(await3).isTrue();
		}
		finally {
			safeStopContainer(container1);
			safeStopContainer(container2);
			safeStopContainer(container3);
			pulsarClient.close();
		}
	}

	@Test
	void keySharedSubscriptionWithDefaultAutoSplitHashingRange() throws Exception {
		DefaultPulsarMessageListenerContainer<String> container1 = null;
		DefaultPulsarMessageListenerContainer<String> container2 = null;
		DefaultPulsarMessageListenerContainer<String> container3 = null;
		PulsarClient pulsarClient = null;
		try {
			pulsarClient = PulsarClient.builder().serviceUrl(PulsarTestContainerSupport.getPulsarBrokerUrl()).build();
			DefaultPulsarConsumerFactory<String> consumerFactory = new DefaultPulsarConsumerFactory<>(pulsarClient,
					Map.of("topicNames", Collections.singleton("key-shared-batch-disabled-topic"), "subscriptionName",
							"key-shared-batch-disabled-sub"));

			CountDownLatch latch = new CountDownLatch(30);
			Map<String, Integer> messageCountByKey1 = new HashMap<>();
			Map<String, Integer> messageCountByKey2 = new HashMap<>();
			Map<String, Integer> messageCountByKey3 = new HashMap<>();

			container1 = createAndStartContainerForKeyShared(consumerFactory, latch, "one", messageCountByKey1);
			container2 = createAndStartContainerForKeyShared(consumerFactory, latch, "two", messageCountByKey2);
			container3 = createAndStartContainerForKeyShared(consumerFactory, latch, "three", messageCountByKey3);
			logger.info("**** Containers all started - pausing for 5s");
			Thread.sleep(5_000);

			DefaultPulsarProducerFactory<String> producerFactory = new DefaultPulsarProducerFactory<>(pulsarClient,
					Map.of("topicName", "key-shared-batch-disabled-topic", "batchingEnabled", "false"));
			PulsarTemplate<String> pulsarTemplate = new PulsarTemplate<>(producerFactory);
			for (int i = 0; i < 10; i++) {
				pulsarTemplate.newMessage("alice-" + i)
						.withMessageCustomizer(messageBuilder -> messageBuilder.key("alice")).send();
				pulsarTemplate.newMessage("buzz-" + i)
						.withMessageCustomizer(messageBuilder -> messageBuilder.key("buzz")).send();
				pulsarTemplate.newMessage("john-" + i)
						.withMessageCustomizer(messageBuilder -> messageBuilder.key("john")).send();
			}
			logger.info("**** Sent all messages");

			// Wait for the all to be consumed
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

			// Make sure that each key group of messages was handled by single container
			assertThat(messageCountByKey1.values()).allMatch((mesasgeCount) -> mesasgeCount == 10);
			assertThat(messageCountByKey2.values()).allMatch((mesasgeCount) -> mesasgeCount == 10);
			assertThat(messageCountByKey3.values()).allMatch((mesasgeCount) -> mesasgeCount == 10);
		}
		finally {
			safeStopContainer(container1);
			safeStopContainer(container2);
			safeStopContainer(container3);
			pulsarClient.close();
		}
	}

	private void safeStopContainer(PulsarMessageListenerContainer container) {
		try {
			container.stop();
		}
		catch (Exception ex) {
			logger.warn(ex, "Failed to stop container %s: %s".formatted(container, ex.getMessage()));
		}
	}

	private DefaultPulsarMessageListenerContainer<String> createAndStartContainerForKeyShared(
			PulsarConsumerFactory<String> consumerFactory, CountDownLatch latch, String containerName,
			Map<String, Integer> messageCountByKey) {

		PulsarContainerProperties containerProps = new PulsarContainerProperties();
		containerProps.setBatchListener(false);

		containerProps.setMessageListener((PulsarRecordMessageListener<?>) (consumer, msg) -> {
			logger.info("CONTAINER(%s) got: %s".formatted(containerName, msg.getValue()));
			messageCountByKey.compute(msg.getKey(), (k, v) -> v != null ? v.intValue() + 1 : 1);
			latch.countDown();
			logger.info("CONTAINER(%s) got: %s - latch count is now %d".formatted(containerName, msg.getValue(),
					latch.getCount()));

		});
		containerProps.setSubscriptionType(SubscriptionType.Key_Shared);
		containerProps.setSchema(Schema.STRING);
		DefaultPulsarMessageListenerContainer<String> container = new DefaultPulsarMessageListenerContainer<>(
				consumerFactory, containerProps);
		container.start();
		return container;
	}

	private DefaultPulsarMessageListenerContainer<String> createAndStartContainerForShared(CountDownLatch latch,
			String message, PulsarConsumerFactory<String> consumerFactory) {
		PulsarContainerProperties pulsarContainerProperties = new PulsarContainerProperties();
		pulsarContainerProperties.setMessageListener((PulsarRecordMessageListener<?>) (consumer, msg) -> {
			assertThat(msg.getValue()).isEqualTo(message);
			latch.countDown();
		});
		pulsarContainerProperties.setSubscriptionType(SubscriptionType.Shared);
		pulsarContainerProperties.setSchema(Schema.STRING);
		DefaultPulsarMessageListenerContainer<String> container = new DefaultPulsarMessageListenerContainer<>(
				consumerFactory, pulsarContainerProperties);
		container.start();
		return container;
	}

}