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

import models.ChannelType.web
import models.request.AuthenticatedClientRequest
import models.request.AuthenticatedRequest
import play.api.mvc.ActionBuilder
import play.api.mvc.ActionRefiner
import play.api.mvc.AnyContent
import play.api.mvc.DefaultActionBuilder
import play.api.mvc.Request
import play.api.mvc.Result

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class FakeAuthenticateClientIdActionProvider @Inject()(defaultActionBuilder: DefaultActionBuilder,
                                                       auth: FakeAuthenticateAction,
                                                       authClientId: FakeAuthenticateClientIdAction)(implicit executionContext: ExecutionContext)
    extends AuthenticatedClientIdActionProvider {

  override def apply(): ActionBuilder[AuthenticatedClientRequest, AnyContent] =
    defaultActionBuilder andThen auth andThen authClientId
}

class FakeAuthenticateClientIdAction extends ActionRefiner[AuthenticatedRequest, AuthenticatedClientRequest] {
  override protected def refine[A](request: AuthenticatedRequest[A]): Future[Either[Result, AuthenticatedClientRequest[A]]] =
    Future.successful(Right(AuthenticatedClientRequest(request, web, "eori", Some("clientId"))))

  override protected def executionContext: ExecutionContext = implicitly[ExecutionContext]
}
