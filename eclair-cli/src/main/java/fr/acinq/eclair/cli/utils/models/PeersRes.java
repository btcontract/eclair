/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.cli.utils.models;

public class PeersRes {

  public String nodeId;
  public String state;
  public String address;
  public Integer channels;

  public PeersRes(String nodeId, String state, String address, Integer channels) {
    this.nodeId = nodeId;
    this.state = state;
    this.address = address;
    this.channels = channels;
  }
}