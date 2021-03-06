// Copyright 2014 Google Inc. All rights reserved.
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
package com.google.devtools.build.lib.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@RunWith(JUnit4.class)
public class CommandFailureUtilsTest {

  @Test
  public void describeCommandError() throws Exception {
    String[] args = new String[40];
    args[0] = "some_command";
    for (int i = 1; i < args.length; i++) {
      args[i] = "arg" + i;
    }
    args[7] = "with spaces"; // Test embedded spaces in argument.
    args[9] = "*";           // Test shell meta characters.
    Map<String, String> env = new HashMap<>();
    env.put("PATH", "/usr/bin:/bin:/sbin");
    env.put("FOO", "foo");
    String cwd = "/my/working/directory";
    String message = CommandFailureUtils.describeCommandError(false, Arrays.asList(args), env, cwd);
    String verboseMessage = CommandFailureUtils.describeCommandError(true, Arrays.asList(args), env,
                                                                     cwd);
    assertEquals(
        "error executing command some_command arg1 "
        + "arg2 arg3 arg4 arg5 arg6 'with spaces' arg8 '*' arg10 "
        + "arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 "
        + "arg19 arg20 arg21 arg22 arg23 arg24 arg25 arg26 "
        + "arg27 arg28 arg29 arg30 arg31 "
        + "... (remaining 8 argument(s) skipped)",
        message);
    assertEquals(
        "error executing command \n"
        + "  (cd /my/working/directory && \\\n"
        + "  exec env - \\\n"
        + "    FOO=foo \\\n"
        + "    PATH=/usr/bin:/bin:/sbin \\\n"
        + "  some_command arg1 arg2 arg3 arg4 arg5 arg6 'with spaces' arg8 '*' arg10 "
        + "arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 "
        + "arg19 arg20 arg21 arg22 arg23 arg24 arg25 arg26 "
        + "arg27 arg28 arg29 arg30 arg31 arg32 arg33 arg34 "
        + "arg35 arg36 arg37 arg38 arg39)",
        verboseMessage);
  }

  @Test
  public void describeCommandFailure() throws Exception {
    String[] args = new String[3];
    args[0] = "/bin/sh";
    args[1] = "-c";
    args[2] = "echo Some errors 1>&2; echo Some output; exit 42";
    Map<String, String> env = new HashMap<>();
    env.put("FOO", "foo");
    env.put("PATH", "/usr/bin:/bin:/sbin");
    String cwd = null;
    String message = CommandFailureUtils.describeCommandFailure(false, Arrays.asList(args),
                                                                env, cwd);
    String verboseMessage = CommandFailureUtils.describeCommandFailure(true, Arrays.asList(args),
                                                                       env, cwd);
    assertEquals(
        "sh failed: error executing command "
        + "/bin/sh -c 'echo Some errors 1>&2; echo Some output; exit 42'",
        message);
    assertEquals(
        "sh failed: error executing command \n"
        + "  (exec env - \\\n"
        + "    FOO=foo \\\n"
        + "    PATH=/usr/bin:/bin:/sbin \\\n"
        + "  /bin/sh -c 'echo Some errors 1>&2; echo Some output; exit 42')",
        verboseMessage);
  }
}
