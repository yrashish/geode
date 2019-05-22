/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.connectors.jdbc.internal.cli;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import org.springframework.shell.core.annotation.CliAvailabilityIndicator;
import org.springframework.shell.core.annotation.CliCommand;
import org.springframework.shell.core.annotation.CliOption;

import org.apache.geode.cache.configuration.CacheConfig;
import org.apache.geode.cache.configuration.CacheElement;
import org.apache.geode.cache.configuration.JndiBindingsType;
import org.apache.geode.cache.configuration.RegionConfig;
import org.apache.geode.connectors.jdbc.internal.configuration.RegionMapping;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.internal.InternalConfigurationPersistenceService;
import org.apache.geode.internal.util.DriverJarUtil;
import org.apache.geode.management.cli.CliMetaData;
import org.apache.geode.management.cli.SingleGfshCommand;
import org.apache.geode.management.internal.cli.commands.CreateJndiBindingCommand;
import org.apache.geode.management.internal.cli.functions.CliFunctionResult;
import org.apache.geode.management.internal.cli.functions.DestroyJndiBindingFunction;
import org.apache.geode.management.internal.cli.i18n.CliStrings;
import org.apache.geode.management.internal.cli.result.model.InfoResultModel;
import org.apache.geode.management.internal.cli.result.model.ResultModel;
import org.apache.geode.management.internal.exceptions.EntityNotFoundException;
import org.apache.geode.management.internal.security.ResourceOperation;
import org.apache.geode.security.ResourcePermission;

public class DestroyDataSourceCommand extends SingleGfshCommand {
  static final String DESTROY_DATA_SOURCE = "destroy data-source";
  static final String DESTROY_DATA_SOURCE_HELP =
      "Destroy a data source that holds a jdbc configuration.";
  static final String DATA_SOURCE_NAME = "name";
  static final String DATA_SOURCE_NAME_HELP = "Name of the data source to be destroyed.";
  static final String IFEXISTS_HELP =
      "Skip the destroy operation when the specified data source does "
          + "not exist. Without this option, an error results from the specification "
          + "of a data source that does not exist.";
  static final String DEREGISTER_DRIVER = "deregister-driver";
  static final String DEREGISTER_DRIVER_HELP =
      "Indicates whether or not the driver class associated"
          + "with the target data source should be deregistered during the destroy process.";

  @CliCommand(value = DESTROY_DATA_SOURCE, help = DESTROY_DATA_SOURCE_HELP)
  @CliMetaData(relatedTopic = CliStrings.TOPIC_GEODE_REGION)
  @ResourceOperation(resource = ResourcePermission.Resource.CLUSTER,
      operation = ResourcePermission.Operation.MANAGE)
  public ResultModel destroyDataSource(
      @CliOption(key = DATA_SOURCE_NAME, mandatory = true,
          help = DATA_SOURCE_NAME_HELP) String dataSourceName,
      @CliOption(key = DEREGISTER_DRIVER,
          help = DEREGISTER_DRIVER_HELP, specifiedDefaultValue = "true",
          unspecifiedDefaultValue = "false") boolean deregisterDriver,
      @CliOption(key = CliStrings.IFEXISTS, help = IFEXISTS_HELP, specifiedDefaultValue = "true",
          unspecifiedDefaultValue = "false") boolean ifExists) {

    InternalConfigurationPersistenceService service =
        (InternalConfigurationPersistenceService) getConfigurationPersistenceService();
    String driverClassName = null;
    if (service != null) {
      List<JndiBindingsType.JndiBinding> bindings =
          service.getCacheConfig("cluster").getJndiBindings();
      JndiBindingsType.JndiBinding binding = CacheElement.findElement(bindings, dataSourceName);
      if (binding == null) {
        throw new EntityNotFoundException(
            CliStrings.format("Data source named \"{0}\" does not exist.", dataSourceName),
            ifExists);
      }

      if (!isDataSource(binding)) {
        return ResultModel.createError(CliStrings.format(
            "Data source named \"{0}\" does not exist. A jndi-binding was found with that name.",
            dataSourceName));
      }

      try {
        checkIfDataSourceIsInUse(service, dataSourceName);
      } catch (IllegalStateException ex) {
        return ResultModel.createError(CliStrings.format(
            "Data source named \"{0}\" is still being used by region \"{1}\". Use destroy jdbc-mapping --region={1} and then try again.",
            dataSourceName, ex.getMessage()));

      }
      if (deregisterDriver) {
        driverClassName = binding.getJdbcDriverClass();
      }
    }

    Set<DistributedMember> targetMembers = findMembers(null, null);
    if (targetMembers.size() > 0) {
      List<CliFunctionResult> dataSourceDestroyResult =
          executeAndGetFunctionResult(new DestroyJndiBindingFunction(),
              new Object[] {dataSourceName, true}, targetMembers);

      if (!ifExists) {
        int resultsNotFound = 0;
        for (CliFunctionResult result : dataSourceDestroyResult) {
          if (result.getStatusMessage().contains("not found")) {
            resultsNotFound++;
          }
        }
        if (resultsNotFound == dataSourceDestroyResult.size()) {
          throw new EntityNotFoundException(
              CliStrings.format("Data source named \"{0}\" does not exist.", dataSourceName),
              ifExists);
        }
      }

      ResultModel result = ResultModel.createMemberStatusResult(dataSourceDestroyResult);
      InfoResultModel infoModel = result.addInfo();
      String deregisterResult = deregisterDriver(deregisterDriver, driverClassName, dataSourceName);
      if (deregisterResult != null) {
        infoModel.addLine(deregisterResult);
      }
      result.setConfigObject(dataSourceName);

      return result;
    } else {
      if (service != null) {
        // ResultModel result =
        // ResultModel
        // .createInfo("No members found, data source removed from cluster configuration.");
        ResultModel result = new ResultModel();
        InfoResultModel infoModel = result.addInfo();
        infoModel.addLine("No members found, data source removed from cluster configuration.");
        String deregisterResult =
            deregisterDriver(deregisterDriver, driverClassName, dataSourceName);
        if (deregisterResult != null) {
          infoModel.addLine(deregisterResult);
        }
        result.setConfigObject(dataSourceName);
        return result;
      } else {
        return ResultModel.createError("No members found and cluster configuration disabled.");
      }
    }
  }

  /**
   * @throws IllegalStateException if the data source is used by a jdbc-mapping. The exception
   *         message names the region using this data source
   */
  private void checkIfDataSourceIsInUse(InternalConfigurationPersistenceService service,
      String dataSourceName) {
    CacheConfig cacheConfig = service.getCacheConfig(null);
    for (RegionConfig regionConfig : cacheConfig.getRegions()) {
      for (CacheElement cacheElement : regionConfig.getCustomRegionElements()) {
        if (cacheElement instanceof RegionMapping) {
          RegionMapping regionMapping = (RegionMapping) cacheElement;
          if (dataSourceName.equals(regionMapping.getDataSourceName())) {
            throw new IllegalStateException(regionConfig.getName());
          }
        }
      }
    }
  }

  private boolean isDataSource(JndiBindingsType.JndiBinding binding) {
    return CreateJndiBindingCommand.DATASOURCE_TYPE.SIMPLE.getType().equals(binding.getType())
        || CreateJndiBindingCommand.DATASOURCE_TYPE.POOLED.getType().equals(binding.getType());
  }

  private String deregisterDriver(boolean deregisterDriver, String driverClassName,
      String dataSourceName) {
    if (deregisterDriver && driverClassName != null) {
      DriverJarUtil util = createDriverJarUtil();
      try {
        util.deregisterDriver(driverClassName);
      } catch (SQLException ex) {
        return "Warning: deregistering \"" + driverClassName + "\" while destroying data source \""
            + dataSourceName + "\" failed with exception: " + ex.getMessage();
      }
    } else if (deregisterDriver && driverClassName == null) {
      return "Warning: deregistering \"" + driverClassName + "\" while destroying data source \""
          + dataSourceName + "\" failed: No driver class name found";
    }
    return null;
  }

  DriverJarUtil createDriverJarUtil() {
    return new DriverJarUtil();
  }

  @Override
  public boolean updateConfigForGroup(String group, CacheConfig config, Object element) {
    CacheElement.removeElement(config.getJndiBindings(), (String) element);
    return true;
  }

  @CliAvailabilityIndicator({DESTROY_DATA_SOURCE})
  public boolean commandAvailable() {
    return isOnlineCommandAvailable();
  }
}
