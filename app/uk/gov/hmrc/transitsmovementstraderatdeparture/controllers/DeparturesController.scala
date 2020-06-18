/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.transitsmovementstraderatdeparture.controllers

import cats.data.NonEmptyList
import javax.inject.Inject
import play.api.Logger
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.transitsmovementstraderatdeparture.controllers.actions.AuthenticateGetOptionalDepartureForWriteActionProvider
import uk.gov.hmrc.transitsmovementstraderatdeparture.models.MessageStatus.SubmissionSucceeded
import uk.gov.hmrc.transitsmovementstraderatdeparture.models.{DepartureId, DepartureStatus, Message, MessageType, SubmissionProcessingResult}
import uk.gov.hmrc.transitsmovementstraderatdeparture.services.{DepartureService, SubmitMessageService}

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.NodeSeq

class DeparturesController @Inject()(
    cc: ControllerComponents,
    authenticatedOptionalDeparture: AuthenticateGetOptionalDepartureForWriteActionProvider,
    departureService: DepartureService,
    submitMessageService: SubmitMessageService)
  (implicit ec: ExecutionContext) extends BackendController(cc) {

  private val allMessageUnsent: NonEmptyList[Message] => Boolean =
    _.map(_.optStatus).forall {
      case Some(messageStatus) if messageStatus != SubmissionSucceeded => true
      case _                                                           => false
    }

  def post: Action[NodeSeq] = authenticatedOptionalDeparture().async(parse.xml) {
    implicit request =>
      request.departure match {
        case Some(departure) if allMessageUnsent(departure.messages) =>
          departureService
            .makeMessageWithStatus(departure.nextMessageCorrelationId, MessageType.DepartureDeclaration)(request.body)
            .map {
              message =>
                submitMessageService
                  .submitMessage(departure.departureId, departure.nextMessageCorrelationId, message, DepartureStatus.DepartureSubmitted)
                  .map {
                    case SubmissionProcessingResult.SubmissionSuccess =>
                      Accepted("Message accepted")
                        .withHeaders("Location" -> routes.DeparturesController.get(departure.departureId).url)

                    case SubmissionProcessingResult.SubmissionFailureInternal => {
                      println("internal fail")
                      InternalServerError
                    }

                    case SubmissionProcessingResult.SubmissionFailureExternal =>
                      BadGateway
                  }
            }
            .getOrElse {
              Logger.warn("Invalid data: missing either DatOfPreMES9, TimOfPreMES10 or DocNumHEA5")
              Future.successful(BadRequest("Invalid data: missing either DatOfPreMES9, TimOfPreMES10 or DocNumHEA5"))
            }

        case _ =>
          departureService.createDeparture(request.eoriNumber)(request.body) match {
            case None =>
              Logger.warn("Invalid data: missing either DatOfPreMES9, TimOfPreMES10 or DocNumHEA5")
              Future.successful(BadRequest("Invalid data: missing either DatOfPreMES9, TimOfPreMES10 or DocNumHEA5"))
            case Some(departureFuture) =>
              departureFuture
                .flatMap {
                  departure =>
                    submitMessageService.submitDeparture(departure).map {
                      case SubmissionProcessingResult.SubmissionSuccess =>
                        Accepted("Message accepted")
                          .withHeaders("Location" -> routes.DeparturesController.get(departure.departureId).url)
                      case SubmissionProcessingResult.SubmissionFailureExternal =>
                        BadGateway
                      case SubmissionProcessingResult.SubmissionFailureInternal =>
                        println("internal fail")
                        InternalServerError
                    }
                }
                .recover {
                  case _ => {
                    println("general fail")
                    InternalServerError
                  }
                }
          }
      }
  }

  def get(departureId: DepartureId): Action[AnyContent] = ???
}
