// Copyright 2025 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.packages;

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading;
import com.google.devtools.build.lib.util.DetailedExitCode;

/**
 * Exception indicating an attempt to access a package piece which is not found, does not exist, or
 * can't be parsed.
 *
 * <p>Prefer using more-specific subclasses, when appropriate.
 */
public class NoSuchPackagePieceException extends NoSuchThingException {

  private final PackagePieceIdentifier packagePieceId;

  public NoSuchPackagePieceException(PackagePieceIdentifier packagePieceId, String message) {
    super(message);
    this.packagePieceId = packagePieceId;
  }

  public NoSuchPackagePieceException(
      PackagePieceIdentifier packagePieceId, String message, Exception cause) {
    super(message, cause);
    this.packagePieceId = packagePieceId;
  }

  public NoSuchPackagePieceException(
      PackagePieceIdentifier packagePieceId, String message, DetailedExitCode detailedExitCode) {
    super(message, detailedExitCode);
    this.packagePieceId = packagePieceId;
  }

  public NoSuchPackagePieceException(
      PackagePieceIdentifier packagePieceId,
      String message,
      Exception cause,
      DetailedExitCode detailedExitCode) {
    super(message, cause, detailedExitCode);
    this.packagePieceId = packagePieceId;
  }

  public PackagePieceIdentifier getPackagePieceIdentifier() {
    return packagePieceId;
  }

  String getRawMessage() {
    return super.getMessage();
  }

  @Override
  public String getMessage() {
    return String.format("no such package piece %s: %s", packagePieceId, getRawMessage());
  }

  public boolean hasExplicitDetailedExitCode() {
    return getUncheckedDetailedExitCode() != null;
  }

  @Override
  public DetailedExitCode getDetailedExitCode() {
    DetailedExitCode uncheckedDetailedExitCode = getUncheckedDetailedExitCode();
    return uncheckedDetailedExitCode != null
        ? uncheckedDetailedExitCode
        : defaultDetailedExitCode();
  }

  private DetailedExitCode defaultDetailedExitCode() {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(getMessage())
            .setPackageLoading(
                // TODO(https://github.com/bazelbuild/bazel/issues/23852): add a new error code?
                PackageLoading.newBuilder().setCode(PackageLoading.Code.PACKAGE_MISSING).build())
            .build());
  }
}
