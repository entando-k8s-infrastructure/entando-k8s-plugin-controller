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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import java.util.Map;
import org.entando.kubernetes.controller.spi.common.LabelNames;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.test.common.SourceLink;
import org.entando.kubernetes.test.common.VariableReferenceAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("component"), @Tag("in-process"), @Tag("allure")})
@Feature("As a deployer, I would like to deploy an EntandoPlugin directly so that I have more granular control over the "
        + "configuration settings")
@Issue("ENG-2284")
@SourceLink("DeployEntandoPluginTest.java")
@SuppressWarnings({"java:S5961"})//because this test is intended to generate documentation and should read like the generated document
class DeployEntandoPluginTest extends PluginTestBase implements VariableReferenceAssertions {

    private EntandoPlugin plugin;

    @Test
    @Description("Should deploy a custom Plugin image with a PostgreSQL database")
    void shouldDeployPluginImageWithPostgresql() {
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
        step("Given that the Operator runs in a Kubernetes environment the requires a filesystem user/group override for mounted volumes",
                () -> attachEnvironmentVariable(EntandoOperatorConfigProperty.ENTANDO_REQUIRES_FILESYSTEM_GROUP_OVERRIDE, "true"));
        step("And the routing suffix has been configured globally ",
                () -> attachEnvironmentVariable(EntandoOperatorConfigProperty.ENTANDO_DEFAULT_ROUTING_SUFFIX, THE_ROUTING_SUFFIX));
        theDefaultTlsSecretWasCreatedAndConfiguredAsDefault();
        step("And there is a controller to process requests for the DBMS capability",
                () -> doAnswer(withADatabaseCapabilityStatus(DbmsVendor.POSTGRESQL, "my_db")).when(client.capabilities())
                        .waitForCapabilityCompletion(argThat(matchesCapability(StandardCapability.DBMS)), anyInt()));
        step("And there is a controller to process requests for the SSO capability",
                () -> doAnswer(withAnSsoCapabilityStatus("mykeycloak.apps.serv.run", "my-realm")).when(client.capabilities())
                        .waitForCapabilityCompletion(argThat(matchesCapability(StandardCapability.SSO)), anyInt()));
        step("When I create an EntandoPlugin with minimal configuration",
                () -> {
                    if (this.plugin.getMetadata().getResourceVersion() != null) {
                        this.plugin = getClient().entandoResources().reload(plugin);
                    }
                    runControllerAgainstCustomResource(plugin);
                });
        final EntandoPlugin entandoPlugin = client.entandoResources().load(EntandoPlugin.class, MY_NAMESPACE, MY_PLUGIN);
        step("Then the Plugin deployment completed successfully", () -> {
            attachKubernetesResource("EntandoPlugin", entandoPlugin);
            assertThat(entandoPlugin.getStatus().getPhase()).isEqualTo(EntandoDeploymentPhase.SUCCESSFUL);
        });
        step("Then a PostgreSQL DBMS Capability was provided :", () -> {
            final ProvidedCapability capability = getClient().entandoResources()
                    .load(ProvidedCapability.class, MY_NAMESPACE, "default-postgresql-dbms-in-namespace");
            assertThat(capability).isNotNull();
            attachKubernetesResource("PostgreSQL DBMS Capability", capability);
        });
        step("Then a Red Hat SSO Capability was provided", () -> {
            final ProvidedCapability capability = getClient().entandoResources()
                    .load(ProvidedCapability.class, MY_NAMESPACE, "default-sso-in-namespace");
            assertThat(capability).isNotNull();
            assertThat(capability.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).get().getExternalBaseUrl())
                    .contains("https://mykeycloak.apps.serv.run/auth");
            assertThat(entandoPlugin.getStatus().getServerStatus(NameUtils.SSO_QUALIFIER)).isPresent();
            attachKubernetesResource(" Red Hat SSO Capability", capability);
        });
        final String dbSecret = "my-plugin-plugindb-secret";
        step("And a database schema was prepared for the Entando Plugin", () -> {
            final Pod mainDbPreprationJob = getClient().pods().loadPod(MY_NAMESPACE,
                    Map.of(LabelNames.RESOURCE_KIND.getName(), "EntandoPlugin", LabelNames.JOB_KIND.getName(), "db-preparation-job",
                            "EntandoPlugin",
                            MY_PLUGIN, LabelNames.DEPLOYMENT_QUALIFIER.getName(), NameUtils.MAIN_QUALIFIER));
            assertThat(mainDbPreprationJob).isNotNull();
            final Container initContainer = theInitContainerNamed("my-plugin-plugindb-schema-creation-job").on(mainDbPreprationJob);
            verifyDbJobAdminCredentials("default-postgresql-dbms-in-namespace-admin-secret", initContainer);
            verifyDbJobSchemaCredentials(dbSecret, initContainer);
        });
        final Deployment thePluginDeployment = client.deployments()
                .loadDeployment(entandoPlugin, NameUtils.standardDeployment(entandoPlugin));
        final Container thePluginContainer = theContainerNamed("server-container").on(thePluginDeployment);
        step("And a Kubernetes Deployment was created reflecting the requirements of a typical Plugin container:", () -> {
            attachKubernetesResource("Deployment", thePluginDeployment);
            step("using the Entando Eap Image",
                    () -> assertThat(thePluginContainer.getImage()).contains("entando/my-plugin:6.2.1"));
            step("With a volume mounted to the standard directory /entando-data",
                    () -> assertThat(theVolumeMountNamed("my-plugin-server-volume").on(thePluginContainer)
                            .getMountPath()).isEqualTo("/entando-data"));
            step("Which is bound to a PersistentVolumeClain", () -> {
                final PersistentVolumeClaim pvc = client.persistentVolumeClaims()
                        .loadPersistentVolumeClaim(entandoPlugin, "my-plugin-server-pvc");
                attachKubernetesResource("PersistentVolumeClaim", pvc);
                assertThat(theVolumeNamed("my-plugin-server-volume").on(thePluginDeployment).getPersistentVolumeClaim()
                        .getClaimName()).isEqualTo(
                        "my-plugin-server-pvc");
            });
            step("And the File System User/Group override " + EntandoPluginServerDeployable.DEFAULT_USER_ID
                    + "has been applied to the mount", () ->
                    assertThat(thePluginDeployment.getSpec().getTemplate().getSpec().getSecurityContext().getFsGroup())
                            .isEqualTo(EntandoPluginServerDeployable.DEFAULT_USER_ID));
            step("And all the variables required to connect to Red Hat SSO have been configured", () -> {
                verifySpringSecurityVariables(thePluginContainer, "https://mykeycloak.apps.serv.run/auth/realms/my-realm",
                        "my-plugin-server-secret");
            });

        });

        step("And a Kubernetes Service was created for the plugin deployment", () -> {
            final Service service = client.services()
                    .loadService(entandoPlugin, NameUtils.standardServiceName(entandoPlugin));
            attachKubernetesResource("Service", service);
            step("Targeting port 8081 in the Deployment",
                    () -> assertThat(service.getSpec().getPorts().get(0).getPort()).isEqualTo(8081));
            step("And with a label selector matching the labels of the Pod Template on the  Deployment",
                    () -> assertThat(service.getSpec().getSelector()).containsAllEntriesOf(
                            Map.of(LabelNames.RESOURCE_KIND.getName(), "EntandoPlugin", "EntandoPlugin",
                                    entandoPlugin.getMetadata().getName(),
                                    LabelNames.DEPLOYMENT.getName(), entandoPlugin.getMetadata().getName())
                    ));
        });

        step("And a Kubernetes Ingress was created:", () -> {
            final Ingress ingress = client.ingresses()
                    .loadIngress(entandoPlugin.getMetadata().getNamespace(), NameUtils.standardIngressName(entandoPlugin));
            attachKubernetesResource("Ingress", ingress);
            step("With a hostname derived from the Plugin name, namespace and the routing suffix", () ->
                    assertThat(ingress.getSpec().getRules().get(0).getHost())
                            .isEqualTo(MY_PLUGIN + "-" + MY_NAMESPACE + "." + THE_ROUTING_SUFFIX));
            step("And the path '/my-plugin' is mapped to the service 'my-plugin-service'", () ->
                    assertThat(theHttpPath("/my-plugin").on(ingress).getBackend().getServiceName()).isEqualTo("my-plugin-service"));
            step("And with TLS configured to use the default TLS secret", () -> {
                assertThat(ingress.getSpec().getTls().get(0).getHosts())
                        .contains(MY_PLUGIN + "-" + MY_NAMESPACE + "." + THE_ROUTING_SUFFIX);
                assertThat(ingress.getSpec().getTls().get(0).getSecretName()).isEqualTo(DEFAULT_TLS_SECRET);
            });
        });

        step("And the default TLS secret was cloned into the EntandoPlugin's deployment namespace", () -> {
            final Secret secret = client.secrets().loadSecret(entandoPlugin, DEFAULT_TLS_SECRET);
            attachKubernetesResource("Default TLS Secret", secret);
            assertThat(secret.getType()).isEqualTo("kubernetes.io/tls");

        });
        attachKubernetesState();
    }

}
