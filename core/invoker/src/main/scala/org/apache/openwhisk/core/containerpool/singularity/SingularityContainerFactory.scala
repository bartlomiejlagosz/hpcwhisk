/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.containerpool.singularity

import akka.actor.ActorSystem

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.containerpool.Container
import org.apache.openwhisk.core.containerpool.ContainerFactory
import org.apache.openwhisk.core.containerpool.ContainerFactoryProvider
import org.apache.openwhisk.core.containerpool.ContainerArgsConfig
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.ExecManifest
import org.apache.openwhisk.core.entity.InvokerInstanceId

import scala.concurrent.duration._
import pureconfig.generic.auto._
import java.util.concurrent.TimeoutException

import pureconfig._
import org.apache.openwhisk.core.ConfigKeys

class SingularityContainerFactory(instance: InvokerInstanceId,
                             parameters: Map[String, Set[String]],
                             containerArgsConfig: ContainerArgsConfig =
                               loadConfigOrThrow[ContainerArgsConfig](ConfigKeys.containerArgs))(
  implicit actorSystem: ActorSystem,
  ec: ExecutionContext,
  logging: Logging,
  singularity: SingularityApiWithFileAccess)
    extends ContainerFactory {

  /** Create a container using singularity cli */
  override def createContainer(tid: TransactionId,
                               name: String,
                               actionImage: ExecManifest.ImageName,
                               userProvidedImage: Boolean,
                               memory: ByteSize,
                               cpuShares: Int)(implicit config: WhiskConfig, logging: Logging): Future[Container] = {
    SingularityContainer.create(
      tid,
      image = if (userProvidedImage) Left(actionImage) else Right(actionImage.localImageName(config.runtimesRegistry)),
//      memory = memory,
//      cpuShares = cpuShares,
//      environment = Map("__OW_API_HOST" -> config.wskApiHost),
//      network = containerArgsConfig.network,
//      dnsServers = containerArgsConfig.dnsServers,
//      dnsSearch = containerArgsConfig.dnsSearch,
//      dnsOptions = containerArgsConfig.dnsOptions,
      name = Some(name),
      parameters ++ containerArgsConfig.extraArgs.map { case (k, v) => ("--" + k, v) })
  }

  /** Perform cleanup on init */
  override def init(): Unit = { return }
  removeAllActionContainers()

  /** Perform cleanup on exit - to be registered as shutdown hook */
  override def cleanup(): Unit = {
    implicit val transid = TransactionId.invoker
    try {
      removeAllActionContainers()
    } catch {
      case e: Exception => logging.error(this, s"Failed to remove action containers: ${e.getMessage}")
    }
  }

  /**
   * Removes all wsk_ containers - regardless of their state
   *
   * If the system in general or Singularity in particular has a very
   * high load, commands may take longer than the specified time
   * resulting in an exception.
   *
   * There is no checking whether container removal was successful
   * or not.
   *
   * @throws InterruptedException     if the current thread is interrupted while waiting
   * @throws TimeoutException         if after waiting for the specified time this `Awaitable` is still not ready
   */
  @throws(classOf[TimeoutException])
  @throws(classOf[InterruptedException])
  private def removeAllActionContainers(): Unit = {
    implicit val transid = TransactionId.invoker
    val cleaning =
      singularity.ps()
        .flatMap {
        containers =>
          val ownedContainers = containers.filter(_.asString.startsWith(s"${ContainerFactory.containerNamePrefix(instance)}_"))
          logging.info(this, s"removing ${ownedContainers.size} action containers.")
          val removals = ownedContainers.map { id =>
            (singularity.unpause(id))
              .recoverWith {
                // Ignore resume failures and try to remove anyway
                case _ => Future.successful(())
              }
              .flatMap { _ =>
                singularity.rm(id)
              }
          }
          Future.sequence(removals)
      }
    Await.ready(cleaning, 30.seconds)
  }
}

object SingularityContainerFactoryProvider extends ContainerFactoryProvider {
  override def instance(actorSystem: ActorSystem,
                        logging: Logging,
                        config: WhiskConfig,
                        instanceId: InvokerInstanceId,
                        parameters: Map[String, Set[String]]): ContainerFactory = {

    new SingularityContainerFactory(instanceId, parameters)(
      actorSystem,
      actorSystem.dispatcher,
      logging,
      new SingularityClientWithFileAccess()(actorSystem.dispatcher)(logging, actorSystem))
  }

}
