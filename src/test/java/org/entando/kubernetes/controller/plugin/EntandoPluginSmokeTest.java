/*
 *
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 *  This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 */

package org.entando.kubernetes.controller.plugin;

import static io.qameta.allure.Allure.attachment;
import static io.qameta.allure.Allure.step;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.support.client.impl.DefaultSimpleK8SClient;
import org.entando.kubernetes.controller.support.client.impl.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.support.client.impl.SupportProducer;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.HttpTestHelper;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.TestFixturePreparation;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.test.common.KeycloakTestCapabilityProvider;
import org.entando.kubernetes.test.e2etest.ControllerExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("smoke"), @Tag("allure"), @Tag("post-deployment"), @Tag("inter-process")})
@Feature("As an Entando Operator users, I want to use a Docker container to process an EntandoPlugin so that I don't need to "
        + "know any of its implementation details to use it.")
class EntandoPluginSmokeTest implements FluentIntegrationTesting {

    private static final String MY_NAMESPACE = EntandoOperatorTestConfig.calculateNameSpace("my-namespace");
    public static final String MY_PLUGIN = EntandoOperatorTestConfig.calculateName("my-plugin");
    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
    private EntandoPlugin entandoPlugin;

    @Test
    @Description("Should successfully connect to newly deployed Entando Plugin")
    void testDeployment() {
        final String ingressHostName = MY_PLUGIN + "-" + MY_NAMESPACE + "." + EntandoOperatorConfig.getDefaultRoutingSuffix()
                .orElse("apps.serv.run");
        final KubernetesClient client = new SupportProducer().getKubernetesClient();
        final DefaultSimpleK8SClient simpleClient = new DefaultSimpleK8SClient(client);
        step("Given I have a clean namespace", () -> {
            TestFixturePreparation.prepareTestFixture(client, deleteAll(EntandoPlugin.class).fromNamespace(MY_NAMESPACE));
        });
        step("And I have configured a ProvidedCapability for SSO in the namespace", () -> {
            final ProvidedCapability providedCapability = new KeycloakTestCapabilityProvider(simpleClient, MY_NAMESPACE)
                    .provideKeycloakCapability();
            attachment("SSO Capability", objectMapper.writeValueAsString(providedCapability));
        });
        step("And I have created an EntandoPlugin custom resource with an Embedded DBMS", () -> {
            this.entandoPlugin = simpleClient.entandoResources()
                    .createOrPatchEntandoResource(
                            new EntandoPluginBuilder()
                                    .withNewMetadata()
                                    .withNamespace(MY_NAMESPACE)
                                    .withName(MY_PLUGIN)
                                    .endMetadata()
                                    .withNewSpec()
                                    .withIngressHostName(ingressHostName)
                                    .withImage("entando/entando-avatar-plugin:6.0.5")
                                    .withHealthCheckPath("/management/health")
                                    .withIngressPath("/avatarPlugin")
                                    .withDbms(DbmsVendor.EMBEDDED)
                                    .endSpec()
                                    .build()
                    );
            attachment("Entando Plugin", objectMapper.writeValueAsString(this.entandoPlugin));
        });
        step("When I run the entando-k8s-plugin-controller container against the EntandoPlugin", () -> {
            ControllerExecutor executor = new ControllerExecutor(MY_NAMESPACE, simpleClient,
                    r -> "entando-k8s-plugin-controller");
            executor.runControllerFor(Action.ADDED, entandoPlugin,
                    EntandoOperatorTestConfig.getVersionOfImageUnderTest().orElse("0.0.0-SNAPSHOT-PR-27-5"));
        });

        step("Then I can successfully access the Plugin's health URL", () -> {
            final String strUrl =
                    HttpTestHelper.getDefaultProtocol() + "://" + ingressHostName
                            + "/avatarPlugin/management/health";
            await().atMost(1, TimeUnit.MINUTES).ignoreExceptions().until(() -> HttpTestHelper.statusOk(strUrl));
            assertThat(HttpTestHelper.statusOk(strUrl)).isTrue();
        });
    }

}
