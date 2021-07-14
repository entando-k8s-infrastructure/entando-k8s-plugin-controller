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

import static java.lang.String.format;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.inject.Inject;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvider;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvisioningResult;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.command.DeploymentProcessor;
import org.entando.kubernetes.controller.spi.common.EntandoControllerException;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.ProvidedDatabaseCapability;
import org.entando.kubernetes.controller.spi.container.ProvidedSsoCapability;
import org.entando.kubernetes.controller.spi.deployable.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.model.capability.CapabilityRequirementBuilder;
import org.entando.kubernetes.model.capability.CapabilityScope;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.capability.StandardCapabilityImplementation;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.ServerStatus;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import picocli.CommandLine;

@CommandLine.Command()
public class EntandoPluginController implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(EntandoPluginController.class.getName());
    private final KubernetesClientForControllers k8sClient;
    private final CapabilityProvider capabilityProvider;
    private final DeploymentProcessor deploymentProcessor;
    private EntandoPlugin entandoPlugin;

    @Inject
    public EntandoPluginController(KubernetesClientForControllers k8sClient, DeploymentProcessor deploymentProcessor,
            CapabilityProvider capabilityProvider) {
        this.k8sClient = k8sClient;
        this.capabilityProvider = capabilityProvider;
        this.deploymentProcessor = deploymentProcessor;
    }

    @Override
    public void run() {
        this.entandoPlugin = (EntandoPlugin) k8sClient.resolveCustomResourceToProcess(Collections.singletonList(EntandoPlugin.class));
        try {
            this.entandoPlugin = k8sClient.deploymentStarted(entandoPlugin);
            final DatabaseConnectionInfo dbConnectionInfo = provideDatabaseIfRequired();
            final SsoConnectionInfo ssoConnectionInfo = provideSso();
            final EntandoPluginServerDeployable deployable = new EntandoPluginServerDeployable(dbConnectionInfo,
                    ssoConnectionInfo, entandoPlugin);
            this.deploymentProcessor.processDeployable(deployable, calculateDbAwareTimeout());
            this.entandoPlugin = k8sClient.deploymentEnded(entandoPlugin);
        } catch (Exception e) {
            entandoPlugin = k8sClient.deploymentFailed(entandoPlugin, e, NameUtils.MAIN_QUALIFIER);
            LOGGER.log(Level.SEVERE, e, () -> format("EntandoPluginController failed:%n%s",
                    entandoPlugin.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).flatMap(ServerStatus::getEntandoControllerFailure)
                            .orElseThrow(IllegalStateException::new)
                            .getDetailMessage()));
        }
        entandoPlugin.getStatus().findFailedServerStatus().flatMap(ServerStatus::getEntandoControllerFailure).ifPresent(s -> {
            throw new CommandLine.ExecutionException(new CommandLine(this), s.getDetailMessage());
        });
    }

    private int calculateDbAwareTimeout() {
        final long timeoutForDbAware;
        if (requiresDbmsService(entandoPlugin.getSpec().getDbms().orElse(DbmsVendor.NONE))) {
            timeoutForDbAware =
                    EntandoOperatorSpiConfig.getPodCompletionTimeoutSeconds() + EntandoOperatorSpiConfig.getPodReadinessTimeoutSeconds();
        } else {
            timeoutForDbAware = EntandoOperatorSpiConfig.getPodReadinessTimeoutSeconds();
        }
        return (int) timeoutForDbAware;
    }

    private ProvidedDatabaseCapability provideDatabaseIfRequired() throws TimeoutException {
        final DbmsVendor dbmsVendor = entandoPlugin.getSpec().getDbms().orElse(DbmsVendor.NONE);
        if (requiresDbmsService(dbmsVendor)) {
            final CapabilityProvisioningResult capabilityResult = capabilityProvider
                    .provideCapability(entandoPlugin, new CapabilityRequirementBuilder()
                            .withCapability(StandardCapability.DBMS)
                            .withImplementation(StandardCapabilityImplementation.valueOf(dbmsVendor.name()))
                            .withResolutionScopePreference(CapabilityScope.NAMESPACE, CapabilityScope.DEDICATED, CapabilityScope.CLUSTER)
                            .build(), 180);
            capabilityResult.getProvidedCapability().getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).ifPresent(s ->
                    this.entandoPlugin = this.k8sClient.updateStatus(entandoPlugin, new ServerStatus(NameUtils.DB_QUALIFIER, s)));
            capabilityResult.getControllerFailure().ifPresent(f -> {
                throw new EntandoControllerException(format("Could not prepare database for EntandoPlugin %s/%s%n%s", entandoPlugin
                                .getMetadata().getNamespace(), entandoPlugin
                                .getMetadata().getName(),
                        f.getDetailMessage()));
            });
            return new ProvidedDatabaseCapability(capabilityResult);
        } else {
            return null;
        }
    }

    private boolean requiresDbmsService(DbmsVendor dbmsVendor) {
        return !Set.of(DbmsVendor.NONE, DbmsVendor.EMBEDDED).contains(dbmsVendor);
    }

    private ProvidedSsoCapability provideSso() throws TimeoutException {
        final CapabilityProvisioningResult capabilityResult = capabilityProvider
                .provideCapability(entandoPlugin, new CapabilityRequirementBuilder()
                        .withCapability(StandardCapability.SSO)
                        .withPreferredDbms(determineDbmsForSso())
                        .withPreferredIngressHostName(entandoPlugin.getSpec().getIngressHostName().orElse(null))
                        .withPreferredTlsSecretName(entandoPlugin.getSpec().getTlsSecretName().orElse(null))
                        .withResolutionScopePreference(CapabilityScope.NAMESPACE, CapabilityScope.CLUSTER)
                        .build(), 240);
        capabilityResult.getProvidedCapability().getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).ifPresent(s ->
                this.entandoPlugin = this.k8sClient.updateStatus(entandoPlugin, new ServerStatus(NameUtils.SSO_QUALIFIER, s)));
        capabilityResult.getControllerFailure().ifPresent(f -> {
            throw new EntandoControllerException(format("Could not prepare SSO for EntandoPlugin %s/%s%n%s", entandoPlugin
                            .getMetadata().getNamespace(), entandoPlugin
                            .getMetadata().getName(),
                    f.getDetailMessage()));
        });
        return new ProvidedSsoCapability(capabilityResult);
    }

    private DbmsVendor determineDbmsForSso() {
        final DbmsVendor dbmsVendor = entandoPlugin.getSpec().getDbms().orElse(DbmsVendor.NONE);
        if (dbmsVendor == DbmsVendor.NONE) {
            return null;
        }
        return dbmsVendor;
    }
}
