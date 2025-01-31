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

package controllers.actions

import models.ChannelType
import models.ChannelType.Api
import models.ChannelType.Web
import play.api.mvc.Request

private[actions] object ChannelUtil {

  def getChannel[A](request: Request[A]): Option[ChannelType] =
    request.headers.get("channel") match {
      case Some(channel) if channel.equals(Api.toString) => Some(Api)
      case Some(channel) if channel.equals(Web.toString) => Some(Web)
      case _                                             => None
    }
}
