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

package models

sealed trait SubmissionProcessingResult {

  def toEither(departure: Departure): Either[ErrorState, SubmissionSuccess] = this match {
    case SubmissionProcessingResult.SubmissionSuccess                 => Right(SubmissionSuccess(departure))
    case SubmissionProcessingResult.SubmissionFailureInternal         => Left(SubmissionFailureInternal)
    case SubmissionProcessingResult.SubmissionFailureExternal         => Left(SubmissionFailureExternal)
    case SubmissionProcessingResult.SubmissionFailureRejected(reason) => Left(SubmissionFailureRejected(reason))
  }
}

object SubmissionProcessingResult {

  case object SubmissionSuccess extends SubmissionProcessingResult

  sealed trait SubmissionFailure extends SubmissionProcessingResult

  case object SubmissionFailureInternal extends SubmissionFailure

  case object SubmissionFailureExternal extends SubmissionFailure

  case class SubmissionFailureRejected(responseBody: String) extends SubmissionFailure

  val values = Seq(
    SubmissionSuccess,
    SubmissionFailureInternal,
    SubmissionFailureExternal
  )
}
