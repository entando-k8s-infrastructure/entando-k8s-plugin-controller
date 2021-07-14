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

import static java.util.Optional.ofNullable;

import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.ConfigurableResourceContainer;
import org.entando.kubernetes.controller.spi.container.DatabaseSchemaConnectionInfo;
import org.entando.kubernetes.controller.spi.container.DbAwareContainer;
import org.entando.kubernetes.controller.spi.container.DeployableContainer;
import org.entando.kubernetes.controller.spi.container.DockerImageInfo;
import org.entando.kubernetes.controller.spi.container.ParameterizableContainer;
import org.entando.kubernetes.controller.spi.container.PersistentVolumeAwareContainer;
import org.entando.kubernetes.controller.spi.container.SpringBootDeployableContainer;
import org.entando.kubernetes.controller.spi.container.SsoAwareContainer;
import org.entando.kubernetes.controller.spi.deployable.SsoClientConfig;
import org.entando.kubernetes.controller.spi.deployable.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoResourceRequirements;
import org.entando.kubernetes.model.common.KeycloakAwareSpec;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;

public class EntandoPluginDeployableContainer implements PersistentVolumeAwareContainer,
        SpringBootDeployableContainer,
        ParameterizableContainer,
        ConfigurableResourceContainer, SsoAwareContainer {

    public static final String PLUGINDB = "plugindb";
    private final EntandoPlugin entandoPlugin;
    private final SsoConnectionInfo ssoConnectionInfo;
    private final List<DatabaseSchemaConnectionInfo> databaseSchemaConnectionInfo;
    private final SsoClientConfig ssoClientConfig;

    public EntandoPluginDeployableContainer(EntandoPlugin entandoPlugin, SsoConnectionInfo ssoConnectionInfo,
            DatabaseConnectionInfo databaseConnectionInfo, SsoClientConfig ssoClientConfig) {
        this.entandoPlugin = entandoPlugin;
        this.ssoConnectionInfo = ssoConnectionInfo;
        this.ssoClientConfig = ssoClientConfig;
        this.databaseSchemaConnectionInfo = ofNullable(databaseConnectionInfo)
                .map(databaseServiceResult1 -> DbAwareContainer
                        .buildDatabaseSchemaConnectionInfo(entandoPlugin, databaseConnectionInfo, Collections.singletonList(PLUGINDB)))
                .orElse(Collections.emptyList());

    }

    @Override
    public Optional<String> getStorageClass() {
        return ofNullable(this.entandoPlugin.getSpec().getStorageClass()
                .orElse(PersistentVolumeAwareContainer.super.getStorageClass().orElse(null)));
    }

    @Override
    public Optional<DatabaseSchemaConnectionInfo> getDatabaseSchema() {
        return databaseSchemaConnectionInfo.stream().findFirst();
    }

    @Override
    public int getCpuLimitMillicores() {
        return 1000;
    }

    @Override
    public int getMemoryLimitMebibytes() {
        return 1024;
    }

    @Override
    public Optional<DbmsVendor> getDbms() {
        return entandoPlugin.getSpec().getDbms();
    }

    @Override
    public SsoClientConfig getSsoClientConfig() {
        return ssoClientConfig;
    }

    @Override
    public List<String> getNamesOfSecretsToMount() {
        return entandoPlugin.getSpec().getConnectionConfigNames();
    }

    @Override
    public DockerImageInfo getDockerImageInfo() {
        return new DockerImageInfo(entandoPlugin.getSpec().getImage());
    }

    @Override
    public String getNameQualifier() {
        return NameUtils.DEFAULT_SERVER_QUALIFIER;
    }

    @Override
    public int getPrimaryPort() {
        return 8081;
    }

    @Override
    public String getVolumeMountPath() {
        return "/entando-data";
    }

    @Override
    public List<EnvVar> getEnvironmentVariables() {
        List<EnvVar> vars = new ArrayList<>();
        vars.add(new EnvVar("PORT", "8081", null));
        vars.add(new EnvVar("SPRING_PROFILES_ACTIVE", "default,prod", null));
        vars.add(new EnvVar("ENTANDO_WIDGETS_FOLDER", "/app/resources/widgets", null));
        vars.add(new EnvVar("ENTANDO_CONNECTIONS_ROOT", DeployableContainer.ENTANDO_SECRET_MOUNTS_ROOT, null));
        vars.add(new EnvVar("ENTANDO_PLUGIN_SECURITY_LEVEL",
                entandoPlugin.getSpec().getSecurityLevel().orElse(PluginSecurityLevel.STRICT).name(), null));
        vars.add(new EnvVar("PLUGIN_SIDECAR_PORT", "8084", null));
        propagateProperty(vars, EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_KIND);
        propagateProperty(vars, EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAMESPACE);
        propagateProperty(vars, EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAME);
        propagateProperty(vars, EntandoOperatorSpiConfigProperty.ENTANDO_CONTROLLER_POD_NAME);
        return vars;
    }

    private void propagateProperty(List<EnvVar> vars, EntandoOperatorSpiConfigProperty prop) {
        EntandoOperatorConfigBase.lookupProperty(prop).ifPresent(s -> vars.add(new EnvVar(prop.name(), s, null)));
    }

    @Override
    public SsoConnectionInfo getSsoConnectionInfo() {
        return this.ssoConnectionInfo;
    }

    @Override
    public Optional<String> getHealthCheckPath() {
        return Optional.of(getWebContextPath() + entandoPlugin.getSpec().getHealthCheckPath());
    }

    @Override
    public String getWebContextPath() {
        return ofNullable(entandoPlugin.getSpec().getIngressPath()).orElse("/" + entandoPlugin.getMetadata().getName());
    }

    @Override
    public List<DatabaseSchemaConnectionInfo> getSchemaConnectionInfo() {
        return this.databaseSchemaConnectionInfo;
    }

    @Override
    public Optional<EntandoResourceRequirements> getResourceRequirementsOverride() {
        return getKeycloakAwareSpec().getResourceRequirements();
    }

    @Override
    public Optional<Integer> getMaximumStartupTimeSeconds() {
        return Optional.of(120);
    }

    @Override
    public List<EnvVar> getEnvironmentVariableOverrides() {
        return getKeycloakAwareSpec().getEnvironmentVariables();
    }

    private KeycloakAwareSpec getKeycloakAwareSpec() {
        return entandoPlugin.getSpec();
    }
}
