// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.analysis;

import com.cloudera.impala.authorization.User;
import com.cloudera.impala.common.AnalysisException;
import com.google.common.base.Strings;

/**
 * Base class for all authorization statements - CREATE/DROP/SHOW ROLE, GRANT/REVOKE
 * ROLE/privilege, etc.
 */
public class AuthorizationStmt extends StatementBase {
  // Set during analysis
  protected User requestingUser_;

  @Override
  public void analyze(Analyzer analyzer) throws AnalysisException {
    if (analyzer.getAuthzConfig().isEnabled()) {
      throw new AnalysisException("Sentry Service is not supported on CDH4");
    }
    if (!analyzer.getAuthzConfig().isEnabled()) {
      throw new AnalysisException("Authorization is not enabled. To enable " +
          "authorization restart Impala with the --server_name=<name> flag.");
    }
    if (analyzer.getAuthzConfig().isFileBasedPolicy()) {
      throw new AnalysisException("Cannot execute authorization statement using a file" +
          " based policy. To disable file based policies, restart Impala without the " +
          "-authorization_policy_file flag set.");
    }
    if (Strings.isNullOrEmpty(analyzer.getUser().getName())) {
      throw new AnalysisException("Cannot execute authorization statement with an " +
          "empty username.");
    }
    requestingUser_ = analyzer.getUser();
  }
}