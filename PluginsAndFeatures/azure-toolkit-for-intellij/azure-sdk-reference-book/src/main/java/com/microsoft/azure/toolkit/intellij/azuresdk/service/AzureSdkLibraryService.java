/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.intellij.azuresdk.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.google.common.collect.ImmutableMap;
import com.microsoft.azure.toolkit.intellij.azuresdk.model.AzureJavaSdkEntity;
import com.microsoft.azure.toolkit.intellij.azuresdk.model.AzureSdkArtifactEntity;
import com.microsoft.azure.toolkit.intellij.azuresdk.model.AzureSdkFeatureEntity;
import com.microsoft.azure.toolkit.intellij.azuresdk.model.AzureSdkServiceEntity;
import com.microsoft.azure.toolkit.lib.common.cache.Cacheable;
import com.microsoft.azure.toolkit.lib.common.cache.Preload;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AzureSdkLibraryService {
    private static final ObjectMapper YML_MAPPER = new YAMLMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ObjectMapper CSV_MAPPER = new CsvMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String SPRING_SDK_METADATA_URL = "https://raw.githubusercontent.com/Azure/azure-sdk-for-java/master/sdk/spring/spring-reference.yml";
    private static final String CLIENT_MGMT_SDK_METADATA_URL = "https://raw.githubusercontent.com/Azure/azure-sdk/master/_data/releases/latest/java-packages.csv";

    @Preload
    @Cacheable(value = "sdk-services", condition = "!(force&&force[0])")
    public static List<AzureSdkServiceEntity> loadAzureSdkServices(boolean... force) throws IOException {
        final Map<String, AzureSdkServiceEntity> services = getSpringSDKEntities().stream()
                .collect(Collectors.toMap(e -> trim(e.getName()), e -> e));
        AzureSdkLibraryService.addClientLibs(services);
        AzureSdkLibraryService.addManagementLibs(services);
        return services.values().stream().sorted(Comparator.comparing(AzureSdkServiceEntity::getName)).collect(Collectors.toList());
    }

    private static void addClientLibs(Map<String, AzureSdkServiceEntity> services) {
        getAzureSDKEntities().stream()
                .filter(raw -> AzureSdkArtifactEntity.Type.CLIENT.equals(raw.getType()))
                .filter(raw -> !Boolean.TRUE.equals(raw.getIsHide()))
                .filter(raw -> "com.azure".equals(raw.getGroupId()))
                .sorted(Comparator.comparing(AzureJavaSdkEntity::getServiceName))
                .forEachOrdered(raw -> {
                    final AzureSdkServiceEntity service = getOrCreateService(services, raw);
                    for (final AzureSdkFeatureEntity feature : service.getContent()) {
                        final boolean specified = Optional.ofNullable(feature.getClientSource())
                                .filter(s -> s.getGroupId().equals(raw.getGroupId()) && s.getArtifactId().equals(raw.getPackageName()))
                                .isPresent();
                        final boolean sameFeatureName = trim(feature.getName()).equals(trim(raw.getDisplayName()));
                        if (specified || sameFeatureName) {
                            feature.getArtifacts().add(toSdkArtifactEntity(raw));
                            return;
                        }
                    }
                    // if no mapping feature found.
                    final AzureSdkFeatureEntity feature = AzureSdkFeatureEntity.builder()
                            .name(raw.getDisplayName())
                            .msdocs(buildMsdocsUrl(raw))
                            .artifacts(new ArrayList<>(Collections.singletonList(toSdkArtifactEntity(raw))))
                            .build();
                    service.getContent().add(feature);
                });
    }

    private static void addManagementLibs(Map<String, AzureSdkServiceEntity> services) {
        getAzureSDKEntities().stream()
                .filter(raw -> !Boolean.TRUE.equals(raw.getIsHide()))
                .filter(raw -> AzureSdkArtifactEntity.Type.MANAGEMENT.equals(raw.getType()))
                .filter(raw -> "com.azure.resourcemanager".equals(raw.getGroupId()))
                .sorted(Comparator.comparing(AzureJavaSdkEntity::getServiceName))
                .forEachOrdered(raw -> {
                    final AzureSdkServiceEntity service = getOrCreateService(services, raw);
                    for (final AzureSdkFeatureEntity feature : service.getContent()) {
                        feature.setName(feature.getName().replace("Resource Management - ", "").trim());
                        feature.getArtifacts().add(toSdkArtifactEntity(raw));
                    }
                });
    }

    private static AzureSdkServiceEntity getOrCreateService(Map<String, AzureSdkServiceEntity> services, AzureJavaSdkEntity raw) {
        final Function<String, AzureSdkServiceEntity> serviceComputer = (key) -> {
            final AzureSdkFeatureEntity feature = AzureSdkFeatureEntity.builder().name(raw.getDisplayName()).msdocs(buildMsdocsUrl(raw)).build();
            return AzureSdkServiceEntity.builder()
                    .name(raw.getServiceName())
                    .content(new ArrayList<>(Collections.singletonList(feature)))
                    .build();
        };
        return services.computeIfAbsent(trim(raw.getServiceName()), serviceComputer);
    }

    private static String trim(String name) {
        return name.toLowerCase().replaceAll("\\W+", "");
    }

    @Nonnull
    private static AzureSdkArtifactEntity toSdkArtifactEntity(@Nonnull AzureJavaSdkEntity entity) {
        final AzureSdkArtifactEntity artifact = new AzureSdkArtifactEntity();
        artifact.setGroupId(entity.getGroupId());
        artifact.setArtifactId(entity.getPackageName());
        artifact.setType(entity.getType());
        artifact.setVersionGA(entity.getVersionGA());
        artifact.setVersionPreview(entity.getVersionPreview());
        artifact.setLinks(buildLinks(entity));
        return artifact;
    }

    /**
     * @see <a href=https://github.com/Azure/azure-sdk/blob/master/eng/README.md#link-templates>Link templates</a>
     */
    private static Map<String, String> buildLinks(AzureJavaSdkEntity entity) {
        final String mavenUrl = buildMavenArtifactUrl(entity);
        final String msdocsUrl = buildMsdocsUrl(entity);
        final String javadocUrl = buildJavadocUrl(entity);
        final String githubUrl = buildGitHubSourceUrl(entity);
        return ImmutableMap.of("github", githubUrl, "repopath", mavenUrl, "msdocs", msdocsUrl, "javadoc", javadocUrl);
    }

    /**
     * {% assign package_url_template = "https://search.maven.org/artifact/item.GroupId/item.Package/item.Version/jar/" %}
     * repopath: https://search.maven.org/artifact/com.azure/azure-security-keyvault-jca
     */
    private static String buildMavenArtifactUrl(AzureJavaSdkEntity entity) {
        return String.format("https://search.maven.org/artifact/%s/%s/", entity.getGroupId(), entity.getPackageName());
    }

    /**
     * {% assign msdocs_url_template =  "https://docs.microsoft.com/java/api/overview/azure/item.TrimmedPackage-readme" %}
     * msdocs: https://docs.microsoft.com/java/api/overview/azure/security-keyvault-jca-readme?view=azure-java-preview
     */
    private static String buildMsdocsUrl(AzureJavaSdkEntity entity) {
        final String url = entity.getMsDocs();
        if ("NA".equals(url)) {
            return "";
        } else if (url.startsWith("http")) {
            return url;
        }
        final String trimmed = entity.getPackageName().replace("azure-", "");
        return String.format("https://docs.microsoft.com/java/api/overview/azure/%s-readme", trimmed);
    }

    /**
     * {% assign ghdocs_url_template = "https://azuresdkdocs.blob.core.windows.net/$web/java/item.Package/item.Version/index.html" %}
     * javadoc: https://azuresdkdocs.blob.core.windows.net/$web/java/azure-security-keyvault-jca/1.0.0-beta.4/index.html
     */
    private static String buildJavadocUrl(AzureJavaSdkEntity entity) {
        final String url = entity.getGhDocs();
        if ("NA".equals(url)) {
            return "";
        } else if (url.startsWith("http")) {
            return url;
        }
        return String.format("https://azuresdkdocs.blob.core.windows.net/$web/java/%s/%s/index.html", entity.getPackageName(), entity.getVersionGA());
    }

    /**
     * {% assign source_url_template = "https://github.com/Azure/azure-sdk-for-java/tree/item.Package_item.Version/sdk/item.RepoPath/item.Package/" %}
     * github: https://github.com/Azure/azure-sdk-for-java/tree/master/sdk/keyvault/azure-security-keyvault-jca
     */
    private static String buildGitHubSourceUrl(final AzureJavaSdkEntity entity) {
        final String url = entity.getRepoPath();
        if ("NA".equals(url)) {
            return "";
        } else if (url.startsWith("http")) {
            return url;
        }
        return String.format("https://github.com/Azure/azure-sdk-for-java/tree/%s_%s/sdk/%s/%s/", entity.getPackageName(), entity.getVersionGA(),
                url, entity.getPackageName());
    }

    @AzureOperation(name = "sdk.load_meta_data", type = AzureOperation.Type.TASK)
    public static List<AzureSdkServiceEntity> getSpringSDKEntities() {
        try {
            final URL destination = new URL(SPRING_SDK_METADATA_URL);
            final ObjectReader reader = YML_MAPPER.readerFor(AzureSdkServiceEntity.class);
            final MappingIterator<AzureSdkServiceEntity> data = reader.readValues(destination);
            return data.readAll();
        } catch (final IOException e) {
            final String message = String.format("failed to load Azure SDK list from \"%s\"", SPRING_SDK_METADATA_URL);
            throw new AzureToolkitRuntimeException(message, e);
        }
    }

    @Cacheable(value = "workspace-tag-azure")
    @AzureOperation(name = "sdk.load_meta_data", type = AzureOperation.Type.TASK)
    public static List<AzureJavaSdkEntity> getAzureSDKEntities() {
        try {
            final URL destination = new URL(CLIENT_MGMT_SDK_METADATA_URL);
            final ObjectReader reader = CSV_MAPPER.readerFor(AzureJavaSdkEntity.class).with(CsvSchema.emptySchema().withHeader());
            final MappingIterator<AzureJavaSdkEntity> data = reader.readValues(destination);
            return data.readAll();
        } catch (final IOException e) {
            final String message = String.format("failed to load Azure SDK list from \"%s\"", CLIENT_MGMT_SDK_METADATA_URL);
            throw new AzureToolkitRuntimeException(message, e);
        }
    }
}
