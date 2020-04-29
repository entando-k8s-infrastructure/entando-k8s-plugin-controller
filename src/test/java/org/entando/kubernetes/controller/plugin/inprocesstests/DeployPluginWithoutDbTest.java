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

package org.entando.kubernetes.controller.plugin.inprocesstests;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.atLeast;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import java.util.Map;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.KeycloakClientConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.SimpleKeycloakClient;
import org.entando.kubernetes.controller.inprocesstest.InProcessTestUtil;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.KeycloakClientConfigArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.NamedArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.SimpleK8SClientDouble;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.controller.plugin.EntandoPluginController;
import org.entando.kubernetes.controller.test.support.FluentTraversals;
import org.entando.kubernetes.model.AbstractServerStatus;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
//in execute component test
@Tag("in-process")

public class DeployPluginWithoutDbTest implements InProcessTestUtil, FluentTraversals {

    public static final String SERVER_PORT = "server-port";
    public static final int PORT_8081 = 8081;
    private static final String MY_PLUGIN_SERVER = MY_PLUGIN + "-server";
    public static final String MY_PLUGIN_SERVER_SECRET = MY_PLUGIN_SERVER + "-secret";
    private static final String MY_PLUGIN_PLUGINDB = MY_PLUGIN + "-plugindb";
    private static final String MY_PLUGIN_PLUGINDB_SECRET = MY_PLUGIN_PLUGINDB + "-secret";
    @Spy
    private final SimpleK8SClient<EntandoResourceClientDouble> client = new SimpleK8SClientDouble();
    @Mock
    private SimpleKeycloakClient keycloakClient;
    private EntandoPluginController entandoPluginController;
    private EntandoPlugin entandoPlugin = new EntandoPluginBuilder(buildTestEntandoPlugin()).editSpec().withDbms(DbmsVendor.NONE).endSpec()
            .build();

    @BeforeEach
    public void putResources() {
        client.secrets().overwriteControllerSecret(buildInfrastructureSecret());
        client.secrets().overwriteControllerSecret(buildInfrastructureSecret());
        client.secrets().overwriteControllerSecret(buildKeycloakSecret());
        entandoPluginController = new EntandoPluginController(client, keycloakClient);
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAME, entandoPlugin.getMetadata().getName());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAMESPACE, entandoPlugin.getMetadata().getNamespace());
        client.entandoResources().putEntandoPlugin(entandoPlugin);
        this.entandoPluginController = new EntandoPluginController(client, keycloakClient);
    }

    @Test
    public void testSecrets() {
        //Given I have an entando plugin
        EntandoPlugin newEntandoPlugin = this.entandoPlugin;
        //And Keycloak is receiving requests
        when(keycloakClient.prepareClientAndReturnSecret(any(KeycloakClientConfig.class))).thenReturn(KEYCLOAK_SECRET);
        //When the EntandoPluginController is notified that a plugin has been add
        entandoPluginController.onStartup(new StartupEvent());

        //Then no K8S Secret was created with for the DB
        NamedArgumentCaptor<Secret> secretCaptor = forResourceNamed(Secret.class, MY_PLUGIN_PLUGINDB_SECRET);
        verify(client.secrets(), never()).createSecretIfAbsent(eq(newEntandoPlugin), secretCaptor.capture());
    }

    @Test
    public void testDeployment() {
        //Given I have configured the controller to use image version 6.0.0 by default
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_DOCKER_IMAGE_VERSION_FALLBACK.getJvmSystemProperty(), "6.0.0");
        //And Keycloak is receiving requests
        when(keycloakClient.prepareClientAndReturnSecret(any(KeycloakClientConfig.class))).thenReturn(KEYCLOAK_SECRET);
        //And I have an entando plugin
        EntandoPlugin newEntandoPlugin = this.entandoPlugin;
        //When the EntandoPluginController is notified that a plugin has been add
        entandoPluginController.onStartup(new StartupEvent());

        //Then K8S was instructed to create a Deployment for both the Plugin JEE Server and the DB
        ArgumentCaptor<Deployment> deploymentCaptor = ArgumentCaptor.forClass(Deployment.class);
        verify(client.deployments()).createOrPatchDeployment(eq(newEntandoPlugin), deploymentCaptor.capture());
        final Deployment serverDeployment = deploymentCaptor.getAllValues().get(0);
        assertThat(serverDeployment.getMetadata().getName(), is(MY_PLUGIN_SERVER + "-deployment"));

        //With a Pod Template that has labels linking it to the previously created K8S Service
        Map<String, String> jeeSelector = serverDeployment.getSpec().getTemplate().getMetadata().getLabels();
        assertThat(jeeSelector.get(DEPLOYMENT_LABEL_NAME), is(MY_PLUGIN_SERVER));
        assertThat(jeeSelector.get(ENTANDO_PLUGIN_LABEL_NAME), is(MY_PLUGIN));

        //Exposing a port 8081 for the JEE Server Container
        Container serverContainer = serverDeployment.getSpec().getTemplate().getSpec().getContainers().get(0);
        assertThat(thePortNamed(SERVER_PORT).on(serverContainer).getContainerPort(), is(PORT_8081));
        assertThat(thePortNamed(SERVER_PORT).on(serverContainer).getProtocol(), is("TCP"));

        // And mapping a persistent volume with a name that reflects the EntandoPlugin and
        // the deployment the Volume is used for
        //That are linked to the previously created PersistentVolumeClaims
        assertThat(theVolumeNamed(MY_PLUGIN_SERVER + "-volume").on(serverDeployment).getPersistentVolumeClaim().getClaimName(),
                is(MY_PLUGIN_SERVER + "-pvc"));

        // and the JEE volument is mounted at /entando-data
        Container pluginContainer = theContainerNamed("server-container").on(serverDeployment);
        assertThat(theVolumeMountNamed(MY_PLUGIN_SERVER + "-volume").on(pluginContainer).getMountPath(),
                is("/entando-data"));
        //And the JEE container uses an image reflecting the custom registry and Entando image version specified
        assertThat(serverContainer.getImage(), is("docker.io/entando/myplugin:6.0.0"));
        assertFalse(pluginContainer.getEnv().stream().anyMatch(envVar -> envVar.getName().equalsIgnoreCase("SPRING_DATASOURCE_URL")));
        assertFalse(pluginContainer.getEnv().stream().anyMatch(envVar -> envVar.getName().equalsIgnoreCase("SPRING_DATASOURCE_USERNAME")));
        assertFalse(pluginContainer.getEnv().stream().anyMatch(envVar -> envVar.getName().equalsIgnoreCase("SPRING_DATASOURCE_PASSWORD")));
        //And Keycloak was configured to support OIDC Integration from the EntandoApp
        verify(keycloakClient).ensureRealm(eq(ENTANDO_KEYCLOAK_REALM));
        verify(keycloakClient, times(2))
                .login(eq(MY_KEYCLOAK_BASE_URL), eq(MY_KEYCLOAK_ADMIN_USERNAME), anyString());
        KeycloakClientConfigArgumentCaptor keycloakClientConfigCaptor = forClientId(MY_PLUGIN_SERVER);
        verify(keycloakClient).prepareClientAndReturnSecret(keycloakClientConfigCaptor.capture());//1 plugin
        assertThat(keycloakClientConfigCaptor.getValue().getClientId(), is(MY_PLUGIN_SERVER));
        assertThat(keycloakClientConfigCaptor.getValue().getRealm(), is("entando"));
        assertThat(keycloakClientConfigCaptor.getValue().getPermissions().get(0).getRole(), is("plugin-admin"));
        //And is configured to use the previously installed Keycloak instance
        String mypluginServerSecret = MY_PLUGIN_SERVER_SECRET;
        verifyKeycloakSettings(pluginContainer, mypluginServerSecret);

        //And the state of both the Deployment was reloaded from K8S
        verify(client.deployments(), times(0)).loadDeployment(eq(newEntandoPlugin), eq(MY_PLUGIN + "-db-deployment"));
        verify(client.deployments()).loadDeployment(eq(newEntandoPlugin), eq(MY_PLUGIN_SERVER + "-deployment"));
        ArgumentCaptor<AbstractServerStatus> statusCaptor = ArgumentCaptor.forClass(AbstractServerStatus.class);

        //And K8S was instructed to update the status of the EntandoPlugin with the status of the server deployment
        verify(client.entandoResources(), atLeast(2)).updateStatus(eq(newEntandoPlugin), statusCaptor.capture());
    }

}