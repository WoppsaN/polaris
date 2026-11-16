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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.polaris.core.config.RealmConfigImpl;
import org.apache.polaris.core.config.RealmConfigurationSource;
import org.apache.polaris.core.storage.CredentialVendingContext;
import org.apache.polaris.core.storage.LocationGrant;
import org.apache.polaris.core.storage.PolarisStorageActions;
import org.apache.polaris.core.storage.StorageAccessConfig;
import org.apache.polaris.core.storage.StorageAccessProperty;
import org.apache.polaris.core.storage.aws.AwsStorageConfigurationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

class StsWebIdentityCredentialVendingMechanismTest {

  private static final String LOCATION = "s3://bucket/base/";

  @TempDir Path tempDir;

  private static AwsStorageConfigurationInfo storageConfig() {
    return AwsStorageConfigurationInfo.builder()
        .roleARN("arn:aws:iam::123456789012:role/r")
        .addAllowedLocation(LOCATION)
        .build();
  }

  private static StsWebIdentityCredentialVendingMechanism mechanism(StsClient stsClient) {
    return mechanism(stsClient, () -> "server-token");
  }

  private static StsWebIdentityCredentialVendingMechanism mechanism(
      StsClient stsClient, Supplier<String> webIdentityToken) {
    return new StsWebIdentityCredentialVendingMechanism(
        destination -> stsClient,
        webIdentityToken,
        null,
        new RealmConfigImpl(RealmConfigurationSource.EMPTY_CONFIG, () -> "test-realm"));
  }

  @Test
  void vendsThroughAssumeRoleWithWebIdentity() {
    StsClient stsClient = mock(StsClient.class);
    ArgumentCaptor<AssumeRoleWithWebIdentityRequest> captor =
        ArgumentCaptor.forClass(AssumeRoleWithWebIdentityRequest.class);
    when(stsClient.assumeRoleWithWebIdentity(captor.capture()))
        .thenReturn(
            AssumeRoleWithWebIdentityResponse.builder()
                .credentials(
                    Credentials.builder()
                        .accessKeyId("accessKey")
                        .secretAccessKey("secretKey")
                        .sessionToken("sess")
                        .build())
                .build());

    StorageAccessConfig accessConfig =
        mechanism(stsClient)
            .integrationFor(storageConfig())
            .getStorageAccessConfig(
                List.of(
                    new LocationGrant(Set.of(LOCATION + "t/"), Set.of(PolarisStorageActions.READ))),
                Optional.empty(),
                CredentialVendingContext.empty());

    assertThat(captor.getValue().webIdentityToken()).isEqualTo("server-token");
    verify(stsClient, never()).assumeRole(any(AssumeRoleRequest.class));
    assertThat(accessConfig.get(StorageAccessProperty.AWS_KEY_ID)).isEqualTo("accessKey");
  }

  @Test
  void readsTheTokenFileAndPicksUpARotatedToken() throws IOException {
    Path file = Files.writeString(tempDir.resolve("token"), "first-token\n");
    assertThat(StsWebIdentityCredentialVendingMechanism.readTokenFile(file.toString()))
        .isEqualTo("first-token");

    Files.writeString(file, "rotated-token");

    assertThat(StsWebIdentityCredentialVendingMechanism.readTokenFile(file.toString()))
        .isEqualTo("rotated-token");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void refusesUnsetTokenFile(String tokenFile) {
    assertThatThrownBy(() -> StsWebIdentityCredentialVendingMechanism.readTokenFile(tokenFile))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "Set polaris.storage.aws.web-identity-token-file for S3 credential vending mechanism"
                + " STS_WEB_IDENTITY");
  }

  @Test
  void refusesMissingTokenFile() {
    Path missing = tempDir.resolve("missing");

    assertThatThrownBy(
            () -> StsWebIdentityCredentialVendingMechanism.readTokenFile(missing.toString()))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessage("Failed to read web identity token file " + missing);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " \n"})
  void refusesAnEmptyTokenFile(String content) throws IOException {
    Path file = Files.writeString(tempDir.resolve("token"), content);

    assertThatThrownBy(
            () -> StsWebIdentityCredentialVendingMechanism.readTokenFile(file.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Web identity token file " + file + " is empty");
  }

  @Test
  void validateAcceptsAReadableToken() {
    assertThatCode(() -> mechanism(mock(StsClient.class)).validate(null, storageConfig()))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"unset", "missing", "empty"})
  void validateRefusesAnUnreadableToken(String problem) throws IOException {
    String tokenFile =
        switch (problem) {
          case "unset" -> null;
          case "missing" -> tempDir.resolve("missing").toString();
          case "empty" -> Files.writeString(tempDir.resolve("token"), "").toString();
          default -> throw new IllegalArgumentException(problem);
        };
    Supplier<String> token =
        () -> StsWebIdentityCredentialVendingMechanism.readTokenFile(tokenFile);
    Throwable readFailure = catchThrowable(token::get);

    assertThatThrownBy(
            () -> mechanism(mock(StsClient.class), token).validate(null, storageConfig()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(readFailure.getMessage())
        .hasCauseInstanceOf(readFailure.getClass());
  }

  @Test
  void vendsWithTheTokenFromTheConfiguredFile() throws IOException {
    Path file = Files.writeString(tempDir.resolve("token"), "configured-token");
    StorageConfiguration storageConfiguration = mock(StorageConfiguration.class);
    StorageConfiguration.AwsStorageConfig aws = mock(StorageConfiguration.AwsStorageConfig.class);
    when(storageConfiguration.aws()).thenReturn(aws);
    when(aws.webIdentityTokenFile()).thenReturn(Optional.of(file));

    StsClient stsClient = mock(StsClient.class);
    ArgumentCaptor<AssumeRoleWithWebIdentityRequest> captor =
        ArgumentCaptor.forClass(AssumeRoleWithWebIdentityRequest.class);
    when(stsClient.assumeRoleWithWebIdentity(captor.capture()))
        .thenReturn(
            AssumeRoleWithWebIdentityResponse.builder()
                .credentials(
                    Credentials.builder()
                        .accessKeyId("accessKey")
                        .secretAccessKey("secretKey")
                        .sessionToken("sess")
                        .build())
                .build());

    new StsWebIdentityCredentialVendingMechanism(
            storageConfiguration,
            destination -> stsClient,
            null,
            new RealmConfigImpl(RealmConfigurationSource.EMPTY_CONFIG, () -> "test-realm"))
        .integrationFor(storageConfig())
        .getStorageAccessConfig(
            List.of(new LocationGrant(Set.of(LOCATION + "t/"), Set.of(PolarisStorageActions.READ))),
            Optional.empty(),
            CredentialVendingContext.empty());

    assertThat(captor.getValue().webIdentityToken()).isEqualTo("configured-token");
  }
}
