/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Gareth Jon Lynch
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.gazbert.bxbot.domain.exchange;

import com.google.common.base.MoreObjects;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Domain object representing the overall Exchange config.
 *
 * @author gazbert
 */
@Schema
public class ExchangeConfig {

  @Schema(
      required = true,
      description =
          "The Exchange name. It is used in log statements to display the Exchange's name."
              + " Value must be an alphanumeric string. Spaces are allowed.")
  private String name;

  @Schema(
      required = true,
      description =
          "The fully qualified name of the Exchange Adapter class for the Trading"
              + " Engine to inject on startup, "
              + "e.g. com.gazbert.bxbot.exchanges.TestExchangeAdapter. The class must be on the"
              + " runtime classpath.")
  private String adapter;

  @Schema(
      description =
          "Optional, but required by the inbuilt Exchange Adapters to configure their exchange "
              + "trading API credentials - see the sample exchange.yaml config files for details")
  private Map<String, String> authenticationConfig;

  @Schema(
      description =
          "Optional, but required by the inbuilt Exchange Adapters to set their network"
              + " configuration.")
  private NetworkConfig networkConfig;

  @Schema(description = "Optional, but required by the inbuilt Exchange Adapters to set "
      + "any additional config, e.g. buy/sell fees.")
  private Map<String, String> otherConfig;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getAdapter() {
    return adapter;
  }

  public void setAdapter(String adapter) {
    this.adapter = adapter;
  }

  public Map<String, String> getAuthenticationConfig() {
    return authenticationConfig;
  }

  public void setAuthenticationConfig(Map<String, String> authenticationConfig) {
    this.authenticationConfig = authenticationConfig;
  }

  public void setNetworkConfig(NetworkConfig networkConfig) {
    this.networkConfig = networkConfig;
  }

  public NetworkConfig getNetworkConfig() {
    return networkConfig;
  }

  public Map<String, String> getOtherConfig() {
    return otherConfig;
  }

  public void setOtherConfig(Map<String, String> otherConfig) {
    this.otherConfig = otherConfig;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("name", name)
        .add("adapter", adapter)
        // authenticationConfig is not included
        .add("networkConfig", networkConfig)
        .add("otherConfig", otherConfig)
        .toString();
  }
}
