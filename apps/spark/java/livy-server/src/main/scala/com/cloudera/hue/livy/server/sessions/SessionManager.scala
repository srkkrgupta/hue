/*
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
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

package com.cloudera.hue.livy.server.sessions

import com.cloudera.hue.livy.Logging
import com.cloudera.hue.livy.sessions.Kind

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}

object SessionManager {
  // Time in milliseconds; TODO: make configurable
  val TIMEOUT = 60000

  // Time in milliseconds; TODO: make configurable
  val GC_PERIOD = 1000 * 60 * 60
}

class SessionManager(factory: SessionFactory) extends Logging {

  private implicit def executor: ExecutionContextExecutor = ExecutionContext.global

  private val sessions = new TrieMap[String, Session]()

  private val garbageCollector = new GarbageCollector(this)
  garbageCollector.start()

  def get(id: String): Option[Session] = {
    sessions.get(id)
  }

  def getSessions = {
    sessions.values
  }

  def getSessionIds = {
    sessions.keys
  }

  def createSession(kind: Kind, proxyUser: Option[String] = None): Future[Session] = {
    val session = factory.createSession(kind, proxyUser = proxyUser)

    session.map({ case(session: Session) =>
      info("created session %s" format session.id)
      sessions.put(session.id, session)
      session
    })
  }

  def shutdown(): Unit = {
    Await.result(Future.sequence(sessions.values.map(delete)), Duration.Inf)
    garbageCollector.shutdown()
  }

  def delete(sessionId: String): Future[Unit] = {
    sessions.get(sessionId) match {
      case Some(session) => delete(session)
      case None => Future.successful(Unit)
    }
  }

  def delete(session: Session): Future[Unit] = {
    session.stop().map { case _ =>
        sessions.remove(session.id)
        Unit
    }
  }

  def collectGarbage() = {
    def expired(session: Session): Boolean = {
      System.currentTimeMillis() - session.lastActivity > SessionManager.TIMEOUT
    }

    sessions.values.filter(expired).foreach(delete)
  }
}

class SessionNotFound extends Exception

private class GarbageCollector(sessionManager: SessionManager) extends Thread {

  private var finished = false

  override def run(): Unit = {
    while (!finished) {
      sessionManager.collectGarbage()
      Thread.sleep(SessionManager.GC_PERIOD)
    }
  }

  def shutdown(): Unit = {
    finished = true
  }
}
