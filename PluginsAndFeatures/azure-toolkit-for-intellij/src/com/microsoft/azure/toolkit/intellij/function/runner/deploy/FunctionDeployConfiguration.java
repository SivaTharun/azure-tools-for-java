/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.intellij.function.runner.deploy;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunProfileWithCompileBeforeLaunchOption;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.microsoft.azure.common.function.configurations.RuntimeConfiguration;
import com.microsoft.azure.management.appservice.FunctionApp;
import com.microsoft.azure.toolkit.intellij.common.AzureRunConfigurationBase;
import com.microsoft.azure.toolkit.intellij.function.FunctionAppComboBoxModel;
import com.microsoft.azure.toolkit.intellij.function.runner.core.FunctionUtils;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static com.microsoft.intellij.ui.messages.AzureBundle.message;

public class FunctionDeployConfiguration extends AzureRunConfigurationBase<FunctionDeployModel>
    implements RunProfileWithCompileBeforeLaunchOption {

    private FunctionDeployModel functionDeployModel;
    private Module module;

    public FunctionDeployConfiguration(@NotNull Project project, @NotNull ConfigurationFactory factory, String name) {
        super(project, factory, name);
        functionDeployModel = new FunctionDeployModel();
    }

    @NotNull
    @Override
    public Module[] getModules() {
        return ModuleManager.getInstance(getProject()).getModules();
    }

    public String getSubscription() {
        return functionDeployModel.getSubscription();
    }

    public String getResourceGroup() {
        return functionDeployModel.getResourceGroup();
    }

    public RuntimeConfiguration getRuntime() {
        return functionDeployModel.getRuntime();
    }

    public String getRegion() {
        return functionDeployModel.getRegion();
    }

    public String getFunctionId() {
        return functionDeployModel.getFunctionId();
    }

    public Map<String, String> getAppSettings() {
        return functionDeployModel.getAppSettings();
    }

    public void setSubscription(String subscription) {
        functionDeployModel.setSubscription(subscription);
    }

    public void setResourceGroup(String resourceGroup) {
        functionDeployModel.setResourceGroup(resourceGroup);
    }

    public String getAppName() {
        return functionDeployModel.getAppName();
    }

    public void setAppName(String appName) {
        functionDeployModel.setAppName(appName);
    }

    public void setRegion(String region) {
        functionDeployModel.setRegion(region);
    }

    public void setPricingTier(String pricingTier) {
        functionDeployModel.setPricingTier(pricingTier);
    }

    public void setFunctionId(String functionId) {
        functionDeployModel.setFunctionId(functionId);
    }

    public void setAppSettings(Map<String, String> appSettings) {
        functionDeployModel.setAppSettings(appSettings);
    }

    public void setTargetFunction(FunctionApp targetFunction) {
        if (targetFunction == null) {
            return;
        }
        setAppName(targetFunction.name());
        setFunctionId(targetFunction.id());
        setResourceGroup(targetFunction.resourceGroupName());
    }

    @Override
    public FunctionDeployModel getModel() {
        return this.functionDeployModel;
    }

    @Override
    public String getTargetName() {
        return null;
    }

    @Override
    public String getTargetPath() {
        return null;
    }

    @Override
    public String getSubscriptionId() {
        return functionDeployModel.getSubscription();
    }

    @NotNull
    @Override
    public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new FunctionDeploymentSettingEditor(getProject(), this);
    }

    @Nullable
    @Override
    public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment executionEnvironment) {
        return new FunctionDeploymentState(getProject(), this);
    }

    public void setDeploymentStagingDirectory(String deploymentStagingDirectory) {
        this.functionDeployModel.setDeploymentStagingDirectoryPath(deploymentStagingDirectory);
    }

    public String getDeploymentStagingDirectory() {
        return this.functionDeployModel.getDeploymentStagingDirectoryPath();
    }

    public Module getModule() {
        return module == null ? FunctionUtils.getFunctionModuleByName(getProject(), functionDeployModel.getModuleName()) : module;
    }

    public void saveTargetModule(Module module) {
        if (module != null) {
            this.module = module;
            functionDeployModel.setModuleName(module.getName());
        }
    }

    public boolean isNewResource() {
        return functionDeployModel.isNewResource();
    }

    public void setNewResource(final boolean newResource) {
        functionDeployModel.setNewResource(newResource);
    }

    public void setAppServicePlanName(final String name) {
        functionDeployModel.setAppServicePlanName(name);
    }

    public void setAppServicePlanResourceGroup(final String resourceGroupName) {
        functionDeployModel.setAppServicePlanResourceGroup(resourceGroupName);
    }

    public void setFunctionDeployModel(final FunctionDeployModel functionDeployModel) {
        this.functionDeployModel = functionDeployModel;
    }

    public String getOs() {
        return functionDeployModel.getOs();
    }

    public void setOs(final String os) {
        functionDeployModel.setOs(os);
    }

    public String getJavaVersion() {
        return functionDeployModel.getJavaVersion();
    }

    public void setJavaVersion(final String javaVersion) {
        functionDeployModel.setJavaVersion(javaVersion);
    }

    public void setInstrumentationKey(String instrumentationKey) {
        this.functionDeployModel.setInstrumentationKey(instrumentationKey);
    }

    public String getInstrumentationKey() {
        return functionDeployModel.getInstrumentationKey();
    }

    public void setInsightsName(String insightsName) {
        this.functionDeployModel.setInsightsName(insightsName);
    }

    public String getInsightsName() {
        return functionDeployModel.getInsightsName();
    }

    public String getAppSettingsKey() {
        return functionDeployModel.getAppSettingsKey();
    }

    public void setAppSettingsKey(String appSettingsStorageKey) {
        functionDeployModel.setAppSettingsKey(appSettingsStorageKey);
    }

    public void saveModel(FunctionAppComboBoxModel functionAppComboBoxModel) {
        if (functionAppComboBoxModel.getFunctionDeployModel() != null) {
            setFunctionDeployModel(functionAppComboBoxModel.getFunctionDeployModel());
        } else {
            functionDeployModel.saveModel(functionAppComboBoxModel);
        }
    }

    @Override
    public void validate() throws ConfigurationException {
        checkAzurePreconditions();
        if (this.module == null) {
            throw new ConfigurationException(message("function.deploy.validate.noModule"));
        }
        if (StringUtils.isEmpty(this.getFunctionId()) && !isNewResource()) {
            throw new ConfigurationException(message("function.deploy.validate.noTarget"));
        }
    }

}
