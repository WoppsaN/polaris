/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.polaris.service.storage;

import com.google.common.annotations.VisibleForTesting;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.apache.polaris.core.config.RealmConfig;
import org.apache.polaris.core.storage.PolarisStorageIntegration;
import org.apache.polaris.core.storage.aws.AwsCredentialsStorageIntegration;
import org.apache.polaris.core.storage.aws.AwsStorageConfigurationInfo;
import org.apache.polaris.core.storage.aws.S3CredentialVendingMechanism;
import org.apache.polaris.core.storage.aws.StsClientProvider;
import org.apache.polaris.core.storage.cache.StorageCredentialCache;
import org.jspecify.annotations.Nullable;

/**
 * STS AssumeRoleWithWebIdentity against the catalog's role, with the Polaris server's own web
 * identity token read from the file set by {@code polaris.storage.aws.web-identity-token-file}. The
 * file is read on every credential mint, so a rotated token is picked up.
 */
@ApplicationScoped
@Identifier(S3CredentialVendingMechanism.STS_WEB_IDENTITY)
public class StsWebIdentityCredentialVendingMechanism implements S3CredentialVendingMechanism {

  private final StsClientProvider stsClientProvider;
  private final Supplier<String> webIdentityToken;
  private final StorageCredentialCache cache;
  private final RealmConfig realmConfig;

  /**
   * For the client proxy ArC generates for this normal-scoped bean: the proxy subclass needs a
   * no-args constructor. A working instance always comes from one of the constructors below.
   */
  protected StsWebIdentityCredentialVendingMechanism() {
    this.stsClientProvider = null;
    this.webIdentityToken = null;
    this.cache = null;
    this.realmConfig = null;
  }

  @Inject
  public StsWebIdentityCredentialVendingMechanism(
      StorageConfiguration storageConfiguration,
      StsClientProvider stsClientProvider,
      StorageCredentialCache cache,
      RealmConfig realmConfig) {
    this(
        stsClientProvider,
        tokenSupplier(
            storageConfiguration.aws().webIdentityTokenFile().map(Path::toString).orElse(null)),
        cache,
        realmConfig);
  }

  /** Test constructor: a fixed token supplier and the realm config to vend under. */
  public StsWebIdentityCredentialVendingMechanism(
      StsClientProvider stsClientProvider,
      Supplier<String> webIdentityToken,
      @Nullable StorageCredentialCache cache,
      RealmConfig realmConfig) {
    this.stsClientProvider = stsClientProvider;
    this.webIdentityToken = webIdentityToken;
    this.cache = cache;
    this.realmConfig = realmConfig;
  }

  @Override
  public PolarisStorageIntegration integrationFor(AwsStorageConfigurationInfo storageConfig) {
    return new AwsCredentialsStorageIntegration(
        stsClientProvider, webIdentityToken, cache, storageConfig, realmConfig);
  }

  /**
   * Refuses a catalog that selects this mechanism when the server cannot read its web identity
   * token, so the misconfiguration surfaces at catalog create and update instead of on first use.
   */
  @Override
  public void validate(
      @Nullable AwsStorageConfigurationInfo current, AwsStorageConfigurationInfo updated) {
    try {
      var unusedToken = webIdentityToken.get();
    } catch (IllegalStateException | UncheckedIOException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  private static Supplier<String> tokenSupplier(@Nullable String tokenFile) {
    return () -> readTokenFile(tokenFile);
  }

  /** Reads the token from {@code tokenFile}. */
  @VisibleForTesting
  static String readTokenFile(@Nullable String tokenFile) {
    if (tokenFile == null || tokenFile.isBlank()) {
      throw new IllegalStateException(
          "Set polaris.storage.aws.web-identity-token-file for S3 credential vending mechanism "
              + STS_WEB_IDENTITY);
    }
    String token;
    try {
      token = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read web identity token file " + tokenFile, e);
    }
    if (token.isEmpty()) {
      throw new IllegalStateException("Web identity token file " + tokenFile + " is empty");
    }
    return token;
  }
}
