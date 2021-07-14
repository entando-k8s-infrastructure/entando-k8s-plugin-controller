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

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.DeployableContainer;
import org.entando.kubernetes.controller.spi.container.KeycloakName;
import org.entando.kubernetes.controller.spi.deployable.DbAwareDeployable;
import org.entando.kubernetes.controller.spi.deployable.SsoAwareDeployable;
import org.entando.kubernetes.controller.spi.deployable.SsoClientConfig;
import org.entando.kubernetes.controller.spi.deployable.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.controller.support.spibase.IngressingDeployableBase;
import org.entando.kubernetes.model.common.KeycloakToUse;
import org.entando.kubernetes.model.plugin.EntandoPlugin;

public class EntandoPluginServerDeployable implements IngressingDeployableBase<EntandoPluginDeploymentResult>,
        DbAwareDeployable<EntandoPluginDeploymentResult>,
        SsoAwareDeployable<EntandoPluginDeploymentResult> {

    public static final String ENTANDO_APP_ROLE = "entandoApp";
    public static final long DEFAULT_USER_ID = 185L;
    private final EntandoPlugin entandoPlugin;
    private final List<DeployableContainer> containers;
    private final SsoConnectionInfo ssoConnectionInfo;

    public EntandoPluginServerDeployable(DatabaseConnectionInfo databaseConnectionInfo,
            SsoConnectionInfo ssoConnectionInfo, EntandoPlugin entandoPlugin) {
        this.entandoPlugin = entandoPlugin;
        this.containers = new ArrayList<>();
        this.ssoConnectionInfo = ssoConnectionInfo;
        this.containers
                .add(new EntandoPluginDeployableContainer(entandoPlugin, ssoConnectionInfo, databaseConnectionInfo, getSsoClientConfig()));
    }

    @Override
    public SsoConnectionInfo getSsoConnectionInfo() {
        return this.ssoConnectionInfo;
    }

    @Override
    public SsoClientConfig getSsoClientConfig() {
        return new SsoClientConfig(determineRealm(entandoPlugin, getSsoConnectionInfo()),
                entandoPlugin.getMetadata().getName() + "-" + NameUtils.DEFAULT_SERVER_QUALIFIER,
                entandoPlugin.getMetadata().getName(), entandoPlugin.getSpec().getRoles(),
                entandoPlugin.getSpec().getPermissions())
                .withRole(ENTANDO_APP_ROLE);
    }

    public static String determineRealm(EntandoPlugin entandoApp, SsoConnectionInfo ssoConnectionInfo) {
        return entandoApp.getSpec().getKeycloakToUse().flatMap(KeycloakToUse::getRealm).or(() -> ssoConnectionInfo.getDefaultRealm())
                .orElse(KeycloakName.ENTANDO_DEFAULT_KEYCLOAK_REALM);
    }

    @Override
    public boolean isIngressRequired() {
        return true;
    }

    @Override
    public Optional<Long> getFileSystemUserAndGroupId() {
        return Optional.of(185L);
    }

    @Override
    public int getReplicas() {
        return entandoPlugin.getSpec().getReplicas().orElse(1);
    }

    @Override
    public String getDefaultServiceAccountName() {
        return "entando-plugin";
    }

    @Override
    public List<DeployableContainer> getContainers() {
        return containers;
    }

    @Override
    public Optional<String> getQualifier() {
        return Optional.empty();
    }

    @Override
    public String getIngressName() {
        return NameUtils.standardIngressName(entandoPlugin);
    }

    @Override
    public String getIngressNamespace() {
        return entandoPlugin.getMetadata().getNamespace();
    }

    @Override
    public EntandoPlugin getCustomResource() {
        return entandoPlugin;
    }

    @Override
    public EntandoPluginDeploymentResult createResult(Deployment deployment, Service service, Ingress ingress, Pod pod) {
        return new EntandoPluginDeploymentResult(pod, service, ingress);
    }

    @Override
    public String getServiceAccountToUse() {
        return entandoPlugin.getSpec().getServiceAccountToUse().orElse(getDefaultServiceAccountName());
    }

}
