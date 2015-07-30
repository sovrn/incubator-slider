/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.slider.providers.agent;

import com.google.common.io.Files;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.registry.client.binding.RegistryUtils;
import org.apache.slider.api.InternalKeys;
import org.apache.slider.api.ResourceKeys;
import org.apache.slider.common.SliderKeys;
import org.apache.slider.common.tools.SliderFileSystem;
import org.apache.slider.common.tools.SliderUtils;
import org.apache.slider.core.conf.AggregateConf;
import org.apache.slider.core.conf.ConfTreeOperations;
import org.apache.slider.core.conf.MapOperations;
import org.apache.slider.core.exceptions.BadConfigException;
import org.apache.slider.core.exceptions.SliderException;
import org.apache.slider.core.launch.AbstractLauncher;
import org.apache.slider.providers.AbstractClientProvider;
import org.apache.slider.providers.ProviderRole;
import org.apache.slider.providers.ProviderUtils;
import org.apache.slider.providers.agent.application.metadata.Application;
import org.apache.slider.providers.agent.application.metadata.Component;
import org.apache.slider.providers.agent.application.metadata.Metainfo;
import org.apache.slider.providers.agent.application.metadata.MetainfoParser;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** This class implements  the client-side aspects of the agent deployer */
public class AgentClientProvider extends AbstractClientProvider
    implements AgentKeys, SliderKeys {


  protected static final Logger log =
      LoggerFactory.getLogger(AgentClientProvider.class);
  protected static final String NAME = "agent";
  private static final ProviderUtils providerUtils = new ProviderUtils(log);
  public static final String E_COULD_NOT_READ_METAINFO
      = "Not a valid app package. Could not read metainfo.";


  protected AgentClientProvider(Configuration conf) {
    super(conf);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public List<ProviderRole> getRoles() {
    return AgentRoles.getRoles();
  }

  @Override //Client
  public void preflightValidateClusterConfiguration(SliderFileSystem sliderFileSystem,
                                                    String clustername,
                                                    Configuration configuration,
                                                    AggregateConf instanceDefinition,
                                                    Path clusterDirPath,
                                                    Path generatedConfDirPath,
                                                    boolean secure) throws
      SliderException,
      IOException {
    super.preflightValidateClusterConfiguration(sliderFileSystem, clustername,
                                                configuration,
                                                instanceDefinition,
                                                clusterDirPath,
                                                generatedConfDirPath, secure);

    String appDef = SliderUtils.getApplicationDefinitionPath(instanceDefinition
        .getAppConfOperations());
    Path appDefPath = new Path(appDef);
    sliderFileSystem.verifyFileExists(appDefPath);

    String agentConf = instanceDefinition.getAppConfOperations().
        getGlobalOptions().getOption(AgentKeys.AGENT_CONF, "");
    if (StringUtils.isNotEmpty(agentConf)) {
      sliderFileSystem.verifyFileExists(new Path(agentConf));
    }

    String appHome = instanceDefinition.getAppConfOperations().
        getGlobalOptions().get(AgentKeys.PACKAGE_PATH);
    if (SliderUtils.isUnset(appHome)) {
      String agentImage = instanceDefinition.getInternalOperations().
          get(InternalKeys.INTERNAL_APPLICATION_IMAGE_PATH);
      sliderFileSystem.verifyFileExists(new Path(agentImage));
    }
  }

  @Override
  public void validateInstanceDefinition(AggregateConf instanceDefinition, SliderFileSystem fs) throws
      SliderException {
    super.validateInstanceDefinition(instanceDefinition, fs);
    log.debug("Validating conf {}", instanceDefinition);
    ConfTreeOperations resources =
        instanceDefinition.getResourceOperations();

    providerUtils.validateNodeCount(instanceDefinition, ROLE_NODE,
                                    0, -1);

    String appDef = null;
    try {
      // Validate the app definition
      appDef = SliderUtils.getApplicationDefinitionPath(instanceDefinition
          .getAppConfOperations());
    } catch (BadConfigException bce) {
      throw new BadConfigException("Application definition must be provided. " + bce.getMessage());
    }

    log.info("Validating app definition {}", appDef);
    String extension = appDef.substring(appDef.lastIndexOf(".") + 1, appDef.length());
    if (!"zip".equals(extension.toLowerCase(Locale.ENGLISH))) {
      throw new BadConfigException("App definition must be packaged as a .zip file. File provided is " + appDef);
    }

    Set<String> names = resources.getComponentNames();
    names.remove(SliderKeys.COMPONENT_AM);
    Map<Integer, String> priorityMap = new HashMap<Integer, String>();

    Metainfo metaInfo = null;
    if (fs != null) {
      try {
        metaInfo = AgentUtils.getApplicationMetainfo(fs, appDef, false);
      } catch (IOException ioe) {
        // Ignore missing metainfo file for now
        log.info("Missing metainfo {}", ioe.getMessage());
      }
    }

    for (String name : names) {
      MapOperations component = resources.getMandatoryComponent(name);

      if (metaInfo != null) {
        Component componentDef = metaInfo.getApplicationComponent(name);
        if (componentDef == null) {
          throw new BadConfigException(
              "Component %s is not a member of application.", name);
        }
      }

      int priority =
          component.getMandatoryOptionInt(ResourceKeys.COMPONENT_PRIORITY);
      if (priority <= 0) {
        throw new BadConfigException("Component %s %s value out of range %d",
                                     name,
                                     ResourceKeys.COMPONENT_PRIORITY,
                                     priority);
      }

      String existing = priorityMap.get(priority);
      if (existing != null) {
        throw new BadConfigException(
            "Component %s has a %s value %d which duplicates that of %s",
            name,
            ResourceKeys.COMPONENT_PRIORITY,
            priority,
            existing);
      }
      priorityMap.put(priority, name);
    }

    // fileSystem may be null for tests
    if (metaInfo != null) {
      for (String name : names) {
        Component componentDef = metaInfo.getApplicationComponent(name);
        if (componentDef == null) {
          throw new BadConfigException(
              "Component %s is not a member of application.", name);
        }

        // ensure that intance count is 0 for client components
        if ("CLIENT".equals(componentDef.getCategory())) {
          MapOperations componentConfig = resources.getMandatoryComponent(name);
          int count =
              componentConfig.getMandatoryOptionInt(ResourceKeys.COMPONENT_INSTANCES);
          if (count > 0) {
            throw new BadConfigException("Component %s is of type CLIENT and cannot be instantiated."
                                         + " Use \"slider client install ...\" command instead.",
                                         name);
          }
        } else {
          MapOperations componentConfig = resources.getMandatoryComponent(name);
          int count =
              componentConfig.getMandatoryOptionInt(ResourceKeys.COMPONENT_INSTANCES);
          int definedMinCount = componentDef.getMinInstanceCountInt();
          int definedMaxCount = componentDef.getMaxInstanceCountInt();
          if (count < definedMinCount || count > definedMaxCount) {
            throw new BadConfigException("Component %s, %s value %d out of range. "
                                         + "Expected minimum is %d and maximum is %d",
                                         name,
                                         ResourceKeys.COMPONENT_INSTANCES,
                                         count,
                                         definedMinCount,
                                         definedMaxCount);
          }
        }
      }
    }
  }


  @Override
  public void prepareAMAndConfigForLaunch(SliderFileSystem fileSystem,
                                          Configuration serviceConf,
                                          AbstractLauncher launcher,
                                          AggregateConf instanceDefinition,
                                          Path snapshotConfDirPath,
                                          Path generatedConfDirPath,
                                          Configuration clientConfExtras,
                                          String libdir,
                                          Path tempPath,
                                          boolean miniClusterTestRun) throws
      IOException,
      SliderException {
    String agentImage = instanceDefinition.getInternalOperations().
        get(InternalKeys.INTERNAL_APPLICATION_IMAGE_PATH);
    if (SliderUtils.isUnset(agentImage)) {
      Path agentPath = new Path(tempPath.getParent(), AgentKeys.PROVIDER_AGENT);
      log.info("Automatically uploading the agent tarball at {}", agentPath);
      fileSystem.getFileSystem().mkdirs(agentPath);
      if (ProviderUtils.addAgentTar(this, AGENT_TAR, fileSystem, agentPath)) {
        instanceDefinition.getInternalOperations().set(
            InternalKeys.INTERNAL_APPLICATION_IMAGE_PATH,
            new Path(agentPath, AGENT_TAR).toUri());
      }
    }
  }

  @Override
  public Set<String> getApplicationTags(SliderFileSystem fileSystem,
                                        String appDef) throws SliderException {
    Set<String> tags;
    Metainfo metainfo;
    try {
      metainfo = AgentUtils.getApplicationMetainfo(fileSystem, appDef, false);
    } catch (IOException e) {
      log.error("Error retrieving metainfo from {}", appDef, e);
      throw new SliderException("Error retrieving metainfo", e);
    }

    if (metainfo == null) {
      log.error("Error retrieving metainfo from {}", appDef);
      throw new SliderException("Error parsing metainfo file, possibly bad structure.");
    }

    Application application = metainfo.getApplication();
    tags = new HashSet<String>();
    tags.add("Name: " + application.getName());
    tags.add("Version: " + application.getVersion());
    tags.add("Description: " + SliderUtils.truncate(application.getComment(), 80));

    return tags;
  }

  @Override
  public void processClientOperation(SliderFileSystem fileSystem,
                                     String operation,
                                     File clientInstallPath,
                                     File appPackage,
                                     JSONObject config,
                                     String name) throws SliderException {
    // create temp folder
    // create sub-folders app_pkg, agent_pkg, command
    File tmpDir = Files.createTempDir();
    log.info("Command is being executed at {}", tmpDir.getAbsolutePath());
    File appPkgDir = new File(tmpDir, "app_pkg");
    appPkgDir.mkdir();

    File agentPkgDir = new File(tmpDir, "agent_pkg");
    agentPkgDir.mkdir();

    File cmdDir = new File(tmpDir, "command");
    cmdDir.mkdir();

    Metainfo metaInfo = null;
    JSONObject defaultConfig = null;
    try {
      // expand app package into /app_pkg
      ZipInputStream zipInputStream = null;
      try {
        zipInputStream = new ZipInputStream(new FileInputStream(appPackage));
        {
          ZipEntry zipEntry = zipInputStream.getNextEntry();
          while (zipEntry != null) {
            if ("metainfo.xml".equals(zipEntry.getName())) {
              int size = (int) zipEntry.getSize();
              if (size != -1) {
                log.info("Reading {} of size {}", zipEntry.getName(),
                         zipEntry.getSize());
                byte[] content = new byte[size];
                int offset = 0;
                while (offset < size) {
                  offset += zipInputStream.read(content, offset, size - offset);
                }
                metaInfo = new MetainfoParser().fromXmlStream(new ByteArrayInputStream(content));
              }
            } else if ("metainfo.json".equals(zipEntry.getName())) {
              int size = (int) zipEntry.getSize();
              if (size != -1) {
                log.info("Reading {} of size {}", zipEntry.getName(),
                         zipEntry.getSize());
                byte[] content = new byte[size];
                int offset = 0;
                while (offset < size) {
                  offset += zipInputStream.read(content, offset, size - offset);
                }
                metaInfo = new MetainfoParser().fromJsonStream(new ByteArrayInputStream(content));
              }
            } else if ("clientInstallConfig-default.json".equals(zipEntry.getName())) {
              int size = (int) zipEntry.getSize();
              if (size != -1) {
                log.info("Reading {} of size {}", zipEntry.getName(),
                         zipEntry.getSize());
                byte[] content = new byte[size];
                int offset = 0;
                while (offset < size) {
                  offset += zipInputStream.read(content, offset, size - offset);
                }
                try {
                  defaultConfig = new JSONObject(new String(content, Charset.defaultCharset()));
                } catch (JSONException jex) {
                  throw new SliderException("Unable to read default client config.", jex);
                }
              }
            }
            String filePath = appPkgDir + File.separator + zipEntry.getName();
            if (!zipEntry.isDirectory()) {
              extractFile(zipInputStream, filePath);
            } else {
              File dir = new File(filePath);
              dir.mkdir();
            }
            zipInputStream.closeEntry();
            zipEntry = zipInputStream.getNextEntry();
          }
        }
      } finally {
        zipInputStream.close();
      }

      if (metaInfo == null) {
        throw new BadConfigException(E_COULD_NOT_READ_METAINFO);
      }

      expandAgentTar(agentPkgDir);

      JSONObject commandJson = getCommandJson(defaultConfig, config, metaInfo, clientInstallPath, name);
      FileWriter file = new FileWriter(new File(cmdDir, "command.json"));
      try {
        file.write(commandJson.toString());

      } catch (IOException e) {
        e.printStackTrace();
      } finally {
        file.flush();
        file.close();
      }

      String client_script = null;
      for (Component component : metaInfo.getApplication().getComponents()) {
        if (component.getCategory().equals("CLIENT")) {
          client_script = component.getCommandScript().getScript();
          log.info("Installing CLIENT {} using script {}", component.getName(), client_script);
          break;
        }
      }

      if (SliderUtils.isUnset(client_script)) {
        throw new SliderException("No valid CLIENT component found. Aborting install.");
      }

      runCommand(appPkgDir, agentPkgDir, cmdDir, client_script);

    } catch (IOException ioex) {
      log.warn("Error while executing INSTALL command {}", ioex.getMessage());
      throw new SliderException("INSTALL client failed.");
    }
  }

  protected void runCommand(
      File appPkgDir,
      File agentPkgDir,
      File cmdDir,
      String clientScript) throws SliderException {
    int exitCode = 0;
    Exception exp = null;
    try {
      String clientScriptPath = appPkgDir.getAbsolutePath() + File.separator + "package" +
                                File.separator + clientScript;
      List<String> command = Arrays.asList(AgentKeys.PYTHON_EXE,
               "-S",
               clientScriptPath,
               "INSTALL",
               cmdDir.getAbsolutePath() + File.separator + "command.json",
               appPkgDir.getAbsolutePath() + File.separator + "package",
               cmdDir.getAbsolutePath() + File.separator + "command-out.json",
               "DEBUG");
      ProcessBuilder pb = new ProcessBuilder(command);
      log.info("Command: " + StringUtils.join(pb.command(), " "));
      pb.environment().put(SliderKeys.PYTHONPATH,
                           agentPkgDir.getAbsolutePath()
                           + File.separator + "slider-agent" + File.pathSeparator
                           + agentPkgDir.getAbsolutePath()
                           + File.separator + "slider-agent/jinja2");
      log.info("{}={}", SliderKeys.PYTHONPATH, pb.environment().get(SliderKeys.PYTHONPATH));

      Process proc = pb.start();
      InputStream stderr = proc.getErrorStream();
      InputStream stdout = proc.getInputStream();
      BufferedReader stdOutReader = new BufferedReader(new InputStreamReader(stdout));
      BufferedReader stdErrReader = new BufferedReader(new InputStreamReader(stderr));

      proc.waitFor();

      String line;
      while ((line = stdOutReader.readLine()) != null) {
        log.info("Stdout: " + line);
      }
      while ((line = stdErrReader.readLine()) != null) {
        log.info("Stderr: " + line);
      }

      exitCode = proc.exitValue();
      log.info("Exit value is {}", exitCode);
    } catch (IOException e) {
      exp = e;
    } catch (InterruptedException e) {
      exp = e;
    }

    if (exitCode != 0) {
      throw new SliderException("INSTALL client failed with exit code " + exitCode);
    }

    if (exp != null) {
      log.error("Error while executing INSTALL command {}. Stack trace {}",
                exp.getMessage(),
                ExceptionUtils.getStackTrace(exp));
      throw new SliderException("INSTALL client failed.", exp);
    }
  }

  private void expandAgentTar(File agentPkgDir) throws IOException {
    String libDirProp =
        System.getProperty(SliderKeys.PROPERTY_LIB_DIR);
    File tarFile = new File(libDirProp, SliderKeys.AGENT_TAR);
    TarArchiveInputStream tarIn = new TarArchiveInputStream(
        new GzipCompressorInputStream(
            new BufferedInputStream(
                new FileInputStream(tarFile)
            )
        )
    );
    try {
      TarArchiveEntry tarEntry = tarIn.getNextTarEntry();
      while (tarEntry != null) {
        File destPath = new File(agentPkgDir, tarEntry.getName());
        if (tarEntry.isDirectory()) {
          destPath.mkdirs();
        } else {
          destPath.createNewFile();
          byte[] byteToRead = new byte[1024];
          BufferedOutputStream buffOut =
              new BufferedOutputStream(new FileOutputStream(destPath));
          try {
            int len;
            while ((len = tarIn.read(byteToRead)) != -1) {
              buffOut.write(byteToRead, 0, len);
            }
          } finally {
            buffOut.close();
          }
        }
        tarEntry = tarIn.getNextTarEntry();
      }
    } finally {
      tarIn.close();
    }
  }

  protected JSONObject getCommandJson(JSONObject defaultConfig,
                                      JSONObject inputConfig,
                                      Metainfo metainfo,
                                      File clientInstallPath,
                                      String name) throws SliderException {
    try {
      JSONObject pkgList = new JSONObject();
      pkgList.put(AgentKeys.PACKAGE_LIST,
                  AgentProviderService.getPackageListFromApplication(metainfo.getApplication()));
      JSONObject obj = new JSONObject();
      obj.put("hostLevelParams", pkgList);

      String user = RegistryUtils.currentUser();
      JSONObject configuration = new JSONObject();
      JSONObject global = new JSONObject();
      global.put("app_install_dir", clientInstallPath.getAbsolutePath());
      global.put("app_user", user);
      if (name != null) {
        global.put("app_name", name);
      }

      if (defaultConfig != null) {
        readConfigEntries(defaultConfig, clientInstallPath, global, name, user);
      }
      if (inputConfig != null) {
        readConfigEntries(inputConfig, clientInstallPath, global, name, user);
      }

      configuration.put("global", global);
      obj.put("configurations", configuration);
      return obj;
    } catch (JSONException jex) {
      log.warn("Error while executing INSTALL command {}", jex.getMessage());
      throw new SliderException("INSTALL client failed.");
    }
  }

  private void readConfigEntries(JSONObject inpConfig,
                                 File clientInstallPath,
                                 JSONObject globalConfig,
                                 String name, String user)
      throws JSONException {
    JSONObject globalSection = inpConfig.getJSONObject("global");
    Iterator it = globalSection.keys();
    while (it.hasNext()) {
      String key = (String) it.next();
      String value = globalSection.getString(key);
      if (SliderUtils.isSet(value)) {
        value = value.replace("{app_install_dir}", clientInstallPath.getAbsolutePath());
        value = value.replace("{app_user}", user);
        if (name != null) {
          value = value.replace("{app_name}", name);
        }
      }
      if (globalConfig.has(key)) {
        // last one wins
        globalConfig.remove(key);
      }
      globalConfig.put(key, value);
    }
  }

  private void extractFile(ZipInputStream zipInputStream, String filePath) throws IOException {
    BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(filePath));
    try {
      byte[] bytesRead = new byte[4096];
      int read = 0;
      while ((read = zipInputStream.read(bytesRead)) != -1) {
        output.write(bytesRead, 0, read);
      }
    } finally {
      output.close();
    }
  }
}
