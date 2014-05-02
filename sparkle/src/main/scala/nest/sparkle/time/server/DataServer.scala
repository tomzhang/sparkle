/* Copyright 2013  Nest Labs

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.  */

package nest.sparkle.time.server

import com.typesafe.config.Config
import akka.actor.Actor
import akka.actor.ActorRefFactory
import nest.sparkle.store.Store
import nest.sparkle.legacy.DataRegistry
import scala.concurrent.ExecutionContextExecutor

/** An actor serving data DataRegistry data via a spray based REST api.  The
  * server is configured with user provided extensions extracted from the config file.
  */
class ConfiguredDataServer(val registry: DataRegistry, val store: Store, val rootConfig: Config) // format: OFF
    extends Actor with ConfiguredDataService { // format: ON
  def actorRefFactory: ActorRefFactory = context
  def receive: Receive = runRoute(route)
  def executionContext: ExecutionContextExecutor = context.dispatcher
  override lazy val webRoot = Some(rootConfig.getString("sparkle-time-server.web-root"))
}
