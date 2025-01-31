/*
 * Copyright 2021 HM Revenue & Customs
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

package config

import java.time.Clock

import com.google.inject.AbstractModule
import controllers.actions._
import repositories.DepartureIdRepository
import repositories.DepartureRepository
import migrations.MigrationRunner
import utils.MessageTranslation
import java.time.Clock

class Module extends AbstractModule {

  override def configure(): Unit = {
    bind(classOf[AppConfig]).asEagerSingleton()
    bind(classOf[AuthenticateActionProvider]).to(classOf[AuthenticateActionProviderImpl]).asEagerSingleton()
    bind(classOf[DepartureRepository]).asEagerSingleton()
    bind(classOf[DepartureIdRepository]).asEagerSingleton()
    bind(classOf[AuthenticatedGetDepartureWithMessagesForReadActionProvider]).to(classOf[AuthenticatedGetDepartureWithMessagesForReadActionProviderImpl])
    bind(classOf[AuthenticatedGetDepartureWithoutMessagesForReadActionProvider]).to(classOf[AuthenticatedGetDepartureWithoutMessagesForReadActionProviderImpl])
    bind(classOf[MessageTranslation]).asEagerSingleton()
    bind(classOf[Clock]).toInstance(Clock.systemUTC)
    bind(classOf[MigrationRunner]).asEagerSingleton()
  }

}
