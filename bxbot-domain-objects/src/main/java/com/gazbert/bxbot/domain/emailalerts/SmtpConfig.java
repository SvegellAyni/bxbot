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

package com.gazbert.bxbot.domain.emailalerts;

import com.google.common.base.MoreObjects;
import io.swagger.v3.oas.annotations.media.Schema;
import javax.validation.constraints.Email;
import javax.validation.constraints.Positive;

/**
 * Domain object representing the SMTP config used for Email Alerts.
 *
 * @author gazbert
 */
@Schema(required = true)
public class SmtpConfig {

  @Schema(required = true, description = "The SMTP hostname.")
  private String host;

  @Schema(required = true, description = "The SMTP TLS port.")
  @Positive(message = "Port must be positive integer")
  private int tlsPort;

  @Schema(required = true, description = "The sender email account name.")
  private String accountUsername;

  @Schema(required = true, description = "The sender email account password.")
  private String accountPassword;

  @Schema(required = true, description = "The email From address.")
  @Email(message = "From Address must be a valid email address")
  private String fromAddress;

  @Schema(required = true, description = "The email To address.")
  @Email(message = "To Address must be a valid email address")
  private String toAddress;

  // required for jackson
  public SmtpConfig() {
  }

  /**
   * Creates a new SmtpConfig.
   *
   * @param host the SMTP host.
   * @param tlsPort the TLS port to use.
   * @param accountUsername the SMTP account name.
   * @param accountPassword the SMTP account password.
   * @param fromAddress the email From address.
   * @param toAddress the email To address.
   */
  public SmtpConfig(
      String host,
      int tlsPort,
      String accountUsername,
      String accountPassword,
      String fromAddress,
      String toAddress) {

    this.host = host;
    this.tlsPort = tlsPort;
    this.accountUsername = accountUsername;
    this.accountPassword = accountPassword;
    this.fromAddress = fromAddress;
    this.toAddress = toAddress;
  }

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public int getTlsPort() {
    return tlsPort;
  }

  public void setTlsPort(int tlsPort) {
    this.tlsPort = tlsPort;
  }

  public String getAccountUsername() {
    return accountUsername;
  }

  public void setAccountUsername(String accountUsername) {
    this.accountUsername = accountUsername;
  }

  public String getAccountPassword() {
    return accountPassword;
  }

  public void setAccountPassword(String accountPassword) {
    this.accountPassword = accountPassword;
  }

  public String getFromAddress() {
    return fromAddress;
  }

  public void setFromAddress(String fromAddress) {
    this.fromAddress = fromAddress;
  }

  public String getToAddress() {
    return toAddress;
  }

  public void setToAddress(String toAddress) {
    this.toAddress = toAddress;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("host", host)
        .add("tlsPort", tlsPort)
        .add("accountUsername", accountUsername)
        // accountPassword is not included
        .add("fromAddress", fromAddress)
        .add("toAddress", toAddress)
        .toString();
  }
}
