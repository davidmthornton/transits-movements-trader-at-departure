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

package controllers

import audit.AuditService
import audit.AuditType._
import cats.data.NonEmptyList
import controllers.actions._
import javax.inject.Inject
import models.MessageStatus.SubmissionSucceeded
import models.MessageType.DepartureDeclaration
import models._
import models.response.ResponseDeparture
import models.response.ResponseDepartures
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import repositories.DepartureRepository
import services._
import uk.gov.hmrc.play.bootstrap.controller.BackendController

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.xml.NodeSeq

class DeparturesController @Inject()(cc: ControllerComponents,
                                     departureRepository: DepartureRepository,
                                     authenticate: AuthenticateActionProvider,
                                     authenticatedOptionalDeparture: AuthenticateGetOptionalDepartureForWriteActionProvider,
                                     authenticatedDepartureForRead: AuthenticatedGetDepartureForReadActionProvider,
                                     departureService: DepartureService,
                                     auditService: AuditService,
                                     submitMessageService: SubmitMessageService)(implicit ec: ExecutionContext)
    extends BackendController(cc) {

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
            .makeMessageWithStatus(departure.departureId, departure.nextMessageCorrelationId, DepartureDeclaration)(request.body) match {
            case Right(message) =>
              submitMessageService
                .submitMessage(departure.departureId, departure.nextMessageCorrelationId, message, DepartureStatus.DepartureSubmitted)
                .map {
                  case SubmissionProcessingResult.SubmissionSuccess =>
                    auditService.auditEvent(DepartureDeclarationSubmitted, request.body)
                    Accepted
                      .withHeaders("Location" -> routes.DeparturesController.get(departure.departureId).url)

                  case SubmissionProcessingResult.SubmissionFailureInternal =>
                    InternalServerError

                  case SubmissionProcessingResult.SubmissionFailureExternal =>
                    BadGateway
                }
                .recover {
                  case _ => {
                    InternalServerError
                  }
                }
            case Left(error) =>
              Logger.warn(error.message)
              Future.successful(BadRequest(error.message))
          }
        case _ =>
          departureService
            .createDeparture(request.eoriNumber, request.body)
            .flatMap {
              case Left(error) =>
                Logger.error(error.message)
                Future.successful(BadRequest(error.message))
              case Right(departure) =>
                submitMessageService
                  .submitDeparture(departure)
                  .map {
                    case SubmissionProcessingResult.SubmissionSuccess =>
                      auditService.auditEvent(DepartureDeclarationSubmitted, request.body)
                      Accepted
                        .withHeaders("Location" -> routes.DeparturesController.get(departure.departureId).url)
                    case SubmissionProcessingResult.SubmissionFailureExternal =>
                      BadGateway
                    case SubmissionProcessingResult.SubmissionFailureInternal =>
                      InternalServerError
                  }
                  .recover {
                    case _ => {
                      InternalServerError
                    }
                  }
            }
            .recover {
              case _ => {
                InternalServerError
              }
            }
      }
  }

  def get(departureId: DepartureId): Action[AnyContent] = authenticatedDepartureForRead(departureId) {
    implicit request =>
      Ok(Json.toJsObject(ResponseDeparture.build(request.departure)))
  }

  def getDepartures(): Action[AnyContent] = authenticate().async {
    implicit request =>
      departureRepository
        .fetchAllDepartures(request.eoriNumber)
        .map {
          allDepartures =>
            Ok(Json.toJsObject(ResponseDepartures(allDepartures.map {
              departure =>
                ResponseDeparture.build(departure)
            })))
        }
        .recover {
          case e =>
            Logger.error(s"Failed to create departure", e)
            InternalServerError
        }
  }
}
