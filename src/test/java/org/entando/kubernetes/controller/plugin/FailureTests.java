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

import static io.qameta.allure.Allure.step;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvider;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.command.DeploymentProcessor;
import org.entando.kubernetes.controller.spi.common.EntandoControllerException;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.deployable.Deployable;
import org.entando.kubernetes.controller.spi.result.ServiceDeploymentResult;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.test.common.LogInterceptor;
import org.entando.kubernetes.test.common.SourceLink;
import org.entando.kubernetes.test.common.ValueHolder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("component"), @Tag("in-process"), @Tag("allure")})
@Feature("As a deployer, I would like to deploy an EntandoPlugin directly so that I have more granular control over the "
        + "configuration settings")
@Issue("ENG-2284")
@SourceLink("FailureTests.java")
@SuppressWarnings({"java:S5961"})//because this test is intended to generate documentation and should read like the generated document
class FailureTests extends PluginTestBase {

    private EntandoPlugin plugin;
    private RuntimeException exceptionToAttach;

    @Override
    public Runnable createController(KubernetesClientForControllers kubernetesClientForControllers, DeploymentProcessor deploymentProcessor,
            CapabilityProvider capabilityProvider) {
        return super.createController(kubernetesClientForControllers, new DeploymentProcessor() {
            @Override
            public <T extends ServiceDeploymentResult<T>> T processDeployable(Deployable<T> deployable, int timeoutSeconds)
                    throws TimeoutException {
                if (exceptionToAttach != null) {
                    getClient().entandoResources().deploymentFailed(deployable.getCustomResource(), exceptionToAttach,
                            NameUtils.MAIN_QUALIFIER);
                    throw new EntandoControllerException("Fake Controller Exception");
                }
                return deploymentProcessor.processDeployable(deployable, timeoutSeconds);
            }
        }, capabilityProvider);
    }

    @Test
    @Description(
            "Should log an exception message occurring during the execution of the DeploymentProcessor and leave it attached to the "
                    + "EntandoPlugin.status")
    void shouldLeaveEarlierExceptionInTactButStillLogIt() {
        this.plugin = new EntandoPluginBuilder()
                .withNewMetadata()
                .withName(MY_PLUGIN)
                .withNamespace(MY_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withDbms(DbmsVendor.EMBEDDED)
                .withImage("entando/my-plugin:6.2.1")
                .endSpec()
                .build();
        step("Given that there is a controller to process requests for the DBMS capability",
                () -> doAnswer(withADatabaseCapabilityStatus(DbmsVendor.MYSQL, "my_db")).when(client.capabilities())
                        .waitForCapabilityCompletion(argThat(matchesCapability(StandardCapability.DBMS)), anyInt()));
        step("And there is a controller to process requests for the SSO capability",
                () -> doAnswer(withAnSsoCapabilityStatus("mykeycloak.apps.serv.run", "my-realm")).when(client.capabilities())
                        .waitForCapabilityCompletion(argThat(matchesCapability(StandardCapability.SSO)), anyInt()));
        step("And the DeploymentProcessor throws a KubernetesClientException when processing the AppBuilder Deployable", () -> {
            this.exceptionToAttach = new KubernetesClientException("Test Exception");
        });
        ValueHolder<Throwable> throwable = new ValueHolder<>();
        step("When I create an EntandoPlugin", () -> throwable.set(catchThrowable(() -> runControllerAgainstCustomResource(plugin))));
        final EntandoPlugin entandoPlugin = client.entandoResources().load(EntandoPlugin.class, MY_NAMESPACE, MY_PLUGIN);
        step("Then the EntandoPlugin deployment failed", () -> {
            assertThat(entandoPlugin.getStatus().getPhase()).isEqualTo(EntandoDeploymentPhase.FAILED);
            attachKubernetesResource("Failed EntandoPlugin", entandoPlugin);
        });
        step(format("And the %s ServerStatus carries the actual failure", NameUtils.MAIN_QUALIFIER), () -> {
            assertThat(
                    entandoPlugin.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).get().getEntandoControllerFailure()
                            .get().getDetailMessage())
                    .contains("Test Exception");
            attachKubernetesResource("Failed EntandoPlugin", entandoPlugin);
        });
        step("And the original exception with the message 'Test Exception' was logged in the EntandoPluginController as SEVERE.",
                () -> {
                    final List<LogRecord> logRecords = LogInterceptor.getLogRecords();
                    final Optional<LogRecord> exception = logRecords.stream()
                            .filter(r -> r.getMessage().contains("Test Exception"))
                            .findFirst();
                    assertThat(exception).isPresent();
                    assertThat(exception.get().getLevel()).isEqualTo(Level.SEVERE);
                });
        step("And a PicoCLI exception was thrown at the top level", () -> {
            assertThat(throwable.get()).isInstanceOf(CommandLine.ExecutionException.class);
        });
        attachKubernetesState();
    }

    @Test
    @Description("Should fail when the database capability provisioning failed")
    void shouldFailWhenTheDatabaseCouldNotBeProvisioned() {
        step("Given I have an EntandoPlugin that requires the PostgreSQL DBMS capability", () -> {

            this.plugin = new EntandoPluginBuilder()
                    .withNewMetadata()
                    .withName(MY_PLUGIN)
                    .withNamespace(MY_NAMESPACE)
                    .endMetadata()
                    .withNewSpec()
                    .withDbms(DbmsVendor.POSTGRESQL)
                    .withImage("entando/my-plugin:6.2.1")
                    .endSpec()
                    .build();
        });
        step("But the controller to process requests for the DBMS capability provides a capability in 'FAILED' state",
                () -> doAnswer(withFailedServerStatus(NameUtils.MAIN_QUALIFIER, new NullPointerException()))
                        .when(client.capabilities())
                        .waitForCapabilityCompletion(argThat(matchesCapability(StandardCapability.DBMS)), anyInt()));
        ValueHolder<Throwable> throwable = new ValueHolder<>();
        step("When I create an EntandoPlugin", () -> throwable.set(catchThrowable(() -> runControllerAgainstCustomResource(plugin))));
        final EntandoPlugin entandoPlugin = client.entandoResources().load(EntandoPlugin.class, MY_NAMESPACE, MY_PLUGIN);
        step("Then the EntandoPlugin deployment failed", () -> {
            assertThat(entandoPlugin.getStatus().getPhase()).isEqualTo(EntandoDeploymentPhase.FAILED);
            attachKubernetesResource("Failed EntandoPlugin", entandoPlugin);
        });
        step("And the 'main' ServerStatus carries the actual failure", () -> {
            assertThat(
                    entandoPlugin.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).get().getEntandoControllerFailure()
                            .get().getDetailMessage())
                    .contains(" Could not prepare database for EntandoPlugin my-namespace/my-plugin");
            attachKubernetesResource("Failed EntandoPlugin", entandoPlugin);
        });
        step("And this exception was logged as SEVERE", () -> {
            assertThat(LogInterceptor.getLogRecords())
                    .anyMatch(r -> r.getMessage().contains(" Could not prepare database for EntandoPlugin my-namespace/my-plugin"));
            final LogRecord logRecord = LogInterceptor.getLogRecords().stream()
                    .filter(r -> r.getMessage().contains(" Could not prepare database for EntandoPlugin my-namespace/my-plugin"))
                    .findFirst()
                    .get();
            assertThat(logRecord.getLevel()).isEqualTo(Level.SEVERE);
            assertThat(logRecord.getThrown()).isInstanceOf(EntandoControllerException.class);
        });
        step("But a PicoCLI exception was thrown at the top level", () -> {
            assertThat(throwable.get()).isInstanceOf(CommandLine.ExecutionException.class);
        });
        attachKubernetesState();
    }

    @Test
    @Description("Should fail when the SSO capability provisioning failed")
    void shouldFailWhenTheSsoServiceCouldNotBeProvisioned() {
        step("Given I have an EntandoPlugin that requires the SSO capability", () -> {
            this.plugin = new EntandoPluginBuilder()
                    .withNewMetadata()
                    .withName(MY_PLUGIN)
                    .withNamespace(MY_NAMESPACE)
                    .endMetadata()
                    .withNewSpec()
                    .withDbms(DbmsVendor.EMBEDDED)
                    .withImage("entando/my-plugin:6.2.1")
                    .withNewKeycloakToUse().withRealm("my-realm").endKeycloakToUse()
                    .endSpec()
                    .build();
        });
        step("But the controller to process requests for the SSO capability provides a capability in 'FAILED' state",
                () -> doAnswer(withFailedServerStatus(NameUtils.MAIN_QUALIFIER, new NullPointerException()))
                        .when(client.capabilities())
                        .waitForCapabilityCompletion(argThat(matchesCapability(StandardCapability.SSO)), anyInt()));
        ValueHolder<Throwable> throwable = new ValueHolder<>();
        step("When I create an EntandoPlugin", () -> throwable.set(catchThrowable(() -> runControllerAgainstCustomResource(plugin))));
        final EntandoPlugin entandoPlugin = client.entandoResources().load(EntandoPlugin.class, MY_NAMESPACE, MY_PLUGIN);
        step("Then the EntandoPlugin deployment failed", () -> {
            assertThat(entandoPlugin.getStatus().getPhase()).isEqualTo(EntandoDeploymentPhase.FAILED);
            attachKubernetesResource("Failed EntandoPlugin", entandoPlugin);
        });
        step("And the 'main' ServerStatus carries the actual failure", () -> {
            assertThat(
                    entandoPlugin.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).get().getEntandoControllerFailure()
                            .get().getDetailMessage())
                    .contains("Could not prepare SSO for EntandoPlugin my-namespace/my-plugin");
            attachKubernetesResource("Failed EntandoPlugin", entandoPlugin);
        });
        step("And this exception was logged as SEVERE", () -> {
            assertThat(LogInterceptor.getLogRecords())
                    .anyMatch(r -> r.getMessage().contains("Could not prepare SSO for EntandoPlugin my-namespace/my-plugin"));
            final LogRecord logRecord = LogInterceptor.getLogRecords().stream()
                    .filter(r -> r.getMessage().contains("Could not prepare SSO for EntandoPlugin my-namespace/my-plugin")).findFirst()
                    .get();
            assertThat(logRecord.getLevel()).isEqualTo(Level.SEVERE);
            assertThat(logRecord.getThrown()).isInstanceOf(EntandoControllerException.class);
        });
        step("But a PicoCLI exception was thrown at the top level", () -> {
            assertThat(throwable.get()).isInstanceOf(CommandLine.ExecutionException.class);
        });
        attachKubernetesState();
    }
}
