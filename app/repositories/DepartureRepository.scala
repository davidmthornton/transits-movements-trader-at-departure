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

package repositories

import cats.syntax.all._
import config.AppConfig
import models._
import models.response.ResponseDeparture
import models.response.ResponseDepartures
import play.api.Configuration
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.api.Cursor
import reactivemongo.api.bson.collection.BSONSerializationPack
import reactivemongo.api.commands.WriteResult
import reactivemongo.api.indexes.Index.Aux
import reactivemongo.api.indexes.IndexType
import reactivemongo.bson.BSONDocument
import reactivemongo.play.json.ImplicitBSONHandlers.JsObjectDocumentWriter
import reactivemongo.play.json.collection.JSONCollection
import utils.IndexUtils

import java.time.Clock
import java.time.LocalDateTime
import java.time.OffsetDateTime
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import metrics.HasMetrics
import com.kenshoo.play.metrics.Metrics

class DepartureRepository @Inject()(
  mongo: ReactiveMongoApi,
  appConfig: AppConfig,
  config: Configuration,
  val metrics: Metrics
)(implicit ec: ExecutionContext, clock: Clock)
    extends MongoDateTimeFormats
    with HasMetrics {

  private lazy val eoriNumberIndex: Aux[BSONSerializationPack.type] = IndexUtils.index(
    key = Seq("eoriNumber" -> IndexType.Ascending),
    name = Some("eori-number-index")
  )

  private lazy val channelIndex: Aux[BSONSerializationPack.type] = IndexUtils.index(
    key = Seq("channel" -> IndexType.Ascending),
    name = Some("channel-index")
  )

  private lazy val referenceNumberIndex: Aux[BSONSerializationPack.type] = IndexUtils.index(
    key = Seq("referenceNumber" -> IndexType.Ascending),
    name = Some("reference-number-index")
  )

  private lazy val lastUpdatedIndex: Aux[BSONSerializationPack.type] = IndexUtils.index(
    key = Seq("lastUpdated" -> IndexType.Ascending),
    name = Some("last-updated-index"),
    options = BSONDocument("expireAfterSeconds" -> appConfig.cacheTtl)
  )

  private lazy val fetchAllIndex: Aux[BSONSerializationPack.type] = IndexUtils.index(
    key = Seq("channel" -> IndexType.Ascending, "eoriNumber" -> IndexType.Ascending),
    name = Some("fetch-all-index")
  )

  private lazy val fetchAllWithDateFilterIndex: Aux[BSONSerializationPack.type] = IndexUtils.index(
    key = Seq("channel" -> IndexType.Ascending, "eoriNumber" -> IndexType.Ascending, "lastUpdated" -> IndexType.Descending),
    name = Some("fetch-all-with-date-filter-index")
  )

  val started: Future[Unit] =
    collection
      .flatMap {
        jsonCollection =>
          for {
            _   <- jsonCollection.indexesManager.ensure(channelIndex)
            _   <- jsonCollection.indexesManager.ensure(eoriNumberIndex)
            _   <- jsonCollection.indexesManager.ensure(referenceNumberIndex)
            _   <- jsonCollection.indexesManager.ensure(fetchAllIndex)
            _   <- jsonCollection.indexesManager.ensure(fetchAllWithDateFilterIndex)
            res <- jsonCollection.indexesManager.ensure(lastUpdatedIndex)
          } yield res
      }
      .map(
        _ => ()
      )

  private lazy val collectionName = DepartureRepository.collectionName

  private def collection: Future[JSONCollection] =
    mongo.database.map(_.collection[JSONCollection](collectionName))

  def bulkInsert(departures: Seq[Departure]): Future[Unit] =
    collection.flatMap {
      _.insert(ordered = false)
        .many(departures.map(Json.toJsObject[Departure]))
        .map(
          _ => ()
        )
    }

  def insert(departure: Departure): Future[Unit] =
    collection.flatMap {
      _.insert(false)
        .one(Json.toJsObject(departure))
        .map(
          _ => ()
        )
    }

  private lazy val featureFlag: Boolean = config.get[Boolean]("feature-flags.testOnly.enabled")

  def getMaxDepartureId: Future[Option[DepartureId]] =
    if (featureFlag) {
      collection.flatMap(
        _.find(Json.obj(), None)
          .sort(Json.obj("_id" -> -1))
          .one[Departure]
          .map(_.map(_.departureId))
      )
    } else Future.successful(None)

  def addNewMessage(departureId: DepartureId, message: Message): Future[Try[Unit]] = {

    val selector = Json.obj(
      "_id" -> departureId
    )

    val modifier =
      Json.obj(
        "$set" -> Json.obj(
          "lastUpdated" -> LocalDateTime.now(clock)
        ),
        "$inc" -> Json.obj(
          "nextMessageCorrelationId" -> 1
        ),
        "$push" -> Json.obj(
          "messages" -> Json.toJson(message)
        )
      )

    collection.flatMap {
      _.findAndUpdate(selector, modifier)
        .map {
          _.lastError
            .map {
              le =>
                if (le.updatedExisting) Success(()) else Failure(new Exception(s"Could not find departure $departureId"))
            }
            .getOrElse(Failure(new Exception("Failed to update departure")))
        }
    }
  }

  @deprecated("Use updateDeparture since this will be removed in the next version", "next")
  def setDepartureStateAndMessageState(
    departureId: DepartureId,
    messageId: MessageId,
    departureState: DepartureStatus,
    messageState: MessageStatus
  ): Future[Option[Unit]] = {
    val selector = DepartureIdSelector(departureId)

    val modifier = CompoundStatusUpdate(DepartureStatusUpdate(departureState), MessageStatusUpdate(messageId, messageState))

    updateDeparture(selector, modifier).map(_.toOption)
  }

  def setMessageState(departureId: DepartureId, messageId: Int, messageStatus: MessageStatus): Future[Try[Unit]] = {
    val selector = Json.obj(
      "$and" -> Json.arr(
        Json.obj("_id"                         -> departureId),
        Json.obj(s"messages.$messageId.status" -> Json.obj("$exists" -> true))
      )
    )

    val modifier = Json.obj(
      "$set" -> Json.obj(
        s"messages.$messageId.status" -> messageStatus.toString
      )
    )

    collection.flatMap {
      _.update(false)
        .one(selector, modifier)
        .map {
          WriteResult
            .lastError(_)
            .map {
              le =>
                if (le.updatedExisting) Success(())
                else
                  Failure(new Exception(le.errmsg match {
                    case Some(err) => err
                    case None      => "Unable to update message status"
                  }))
            }
            .getOrElse(Failure(new Exception("Unable to update message status")))
        }
    }
  }

  def get(departureId: DepartureId): Future[Option[Departure]] = {

    val selector = Json.obj(
      "_id" -> departureId
    )

    collection.flatMap {
      _.find(selector, None)
        .one[Departure]
    }
  }

  def get(departureId: DepartureId, channelFilter: ChannelType): Future[Option[Departure]] = {

    val selector = Json.obj(
      "_id"     -> departureId,
      "channel" -> channelFilter
    )

    collection.flatMap {
      _.find(selector, None)
        .one[Departure]
    }
  }

  def getWithoutMessages(departureId: DepartureId): Future[Option[DepartureWithoutMessages]] = {
    val nextMessageId = Json.obj("nextMessageId" -> Json.obj("$size" -> "$messages"))

    val projection = DepartureWithoutMessages.projection ++ nextMessageId

    collection
      .flatMap {
        c =>
          c.aggregateWith[DepartureWithoutMessages](allowDiskUse = true) {
              _ =>
                import c.aggregationFramework._

                val initialFilter: PipelineOperator =
                  Match(Json.obj("_id" -> departureId))

                val transformations = List[PipelineOperator](Project(projection))
                (initialFilter, transformations)

            }
            .headOption

      }
      .map(opt => opt.map(d => d.copy(nextMessageId = MessageId(d.nextMessageId.value + 1))))

  }

  def getWithoutMessages(departureId: DepartureId, channelFilter: ChannelType): Future[Option[DepartureWithoutMessages]] = {
    val nextMessageId = Json.obj("nextMessageId" -> Json.obj("$size" -> "$messages"))

    val projection = DepartureWithoutMessages.projection ++ nextMessageId

    collection
      .flatMap {
        c =>
          c.aggregateWith[DepartureWithoutMessages](allowDiskUse = true) {
              _ =>
                import c.aggregationFramework._

                val initialFilter: PipelineOperator =
                  Match(Json.obj("_id" -> departureId, "channel" -> channelFilter))

                val transformations = List[PipelineOperator](Project(projection))
                (initialFilter, transformations)

            }
            .headOption

      }
      .map(opt => opt.map(d => d.copy(nextMessageId = MessageId(d.nextMessageId.value + 1))))
  }

  def getMessage(departureId: DepartureId, channelFilter: ChannelType, messageId: MessageId): Future[Option[Message]] =
    collection.flatMap {
      c =>
        c.aggregateWith[Message](allowDiskUse = true) {
            _ =>
              import c.aggregationFramework._

              val initialFilter: PipelineOperator =
                Match(
                  Json.obj("_id" -> departureId, "channel" -> channelFilter, "messages" -> Json.obj("$elemMatch" -> Json.obj("messageId" -> messageId.value)))
                )

              val unwindMessages = List[PipelineOperator](
                Unwind(
                  path = "messages",
                  includeArrayIndex = None,
                  preserveNullAndEmptyArrays = None
                )
              )

              val secondaryFilter = List[PipelineOperator](Match(Json.obj("messages.messageId" -> messageId.value)))

              val groupById = List[PipelineOperator](GroupField("_id")("messages" -> FirstField("messages")))

              val replaceRoot = List[PipelineOperator](ReplaceRootField("messages"))

              val transformations = unwindMessages ++ secondaryFilter ++ groupById ++ replaceRoot

              (initialFilter, transformations)

          }
          .headOption
    }

  def addResponseMessage(departureId: DepartureId, message: Message, status: DepartureStatus): Future[Try[Unit]] = {
    val selector = Json.obj(
      "_id" -> departureId
    )

    val modifier =
      Json.obj(
        "$set" -> Json.obj(
          "lastUpdated" -> LocalDateTime.now(clock),
          "status"      -> status.toString
        ),
        "$push" -> Json.obj(
          "messages" -> Json.toJson(message)
        )
      )

    collection.flatMap {
      _.findAndUpdate(selector, modifier)
        .map {
          _.lastError
            .map {
              le =>
                if (le.updatedExisting) Success(()) else Failure(new Exception(s"Could not find departure $departureId"))
            }
            .getOrElse(Failure(new Exception("Failed to update departure")))
        }
    }
  }

  def setMrnAndAddResponseMessage(departureId: DepartureId, message: Message, status: DepartureStatus, mrn: MovementReferenceNumber): Future[Try[Unit]] = {
    val selector = Json.obj(
      "_id" -> departureId
    )

    val modifier =
      Json.obj(
        "$set" -> Json.obj(
          "lastUpdated"             -> LocalDateTime.now(clock),
          "movementReferenceNumber" -> mrn,
          "status"                  -> status.toString
        ),
        "$push" -> Json.obj(
          "messages" -> Json.toJson(message)
        )
      )

    collection.flatMap {
      _.findAndUpdate(selector, modifier)
        .map {
          _.lastError
            .map {
              le =>
                if (le.updatedExisting) Success(()) else Failure(new Exception(s"Could not find departure $departureId"))
            }
            .getOrElse(Failure(new Exception("Failed to update departure")))
        }
    }
  }

  def fetchAllDepartures(
    eoriNumber: String,
    channelFilter: ChannelType,
    updatedSince: Option[OffsetDateTime],
    lrn: Option[String] = None,
    pageSize: Option[Int] = None,
    page: Option[Int] = None
  ): Future[ResponseDepartures] =
    withMetricsTimerAsync("mongo-get-departures-for-eori") {
      _ =>
        val baseSelector = Json.obj("eoriNumber" -> eoriNumber, "channel" -> channelFilter)

        val dateSelector = updatedSince
          .map {
            dateTime =>
              Json.obj("lastUpdated" -> Json.obj("$gte" -> dateTime))
          }
          .getOrElse {
            Json.obj()
          }

        val lrnSelector = lrn
          .map {
            lrn =>
              Json.obj("referenceNumber" -> Json.obj("$regex" -> lrn))
          }
          .getOrElse {
            Json.obj()
          }

        val fullSelector =
          baseSelector ++ dateSelector ++ lrnSelector

        val nextMessageId = Json.obj("nextMessageId" -> Json.obj("$size" -> "$messages"))

        val projection = DepartureWithoutMessages.projection ++ nextMessageId

        val limit = pageSize.map(Math.max(1, _)).getOrElse(appConfig.maxRowsReturned(channelFilter))

        val skip = Math.abs(page.getOrElse(1) - 1) * limit

        collection.flatMap {
          coll =>
            val fetchCount      = coll.count(Some(baseSelector))
            val fetchMatchCount = coll.count(Some(fullSelector))

            val fetchResults = coll
              .aggregateWith[DepartureWithoutMessages](allowDiskUse = true) {
                _ =>
                  import coll.aggregationFramework._

                  val matchStage   = Match(fullSelector)
                  val projectStage = Project(projection)
                  val sortStage    = Sort(Descending("lastUpdated"))
                  val skipStage    = Skip(skip)
                  val limitStage   = Limit(limit)

                  val restStages =
                    if (skip > 0)
                      List[PipelineOperator](projectStage, sortStage, skipStage, limitStage)
                    else
                      List[PipelineOperator](projectStage, sortStage, limitStage)

                  (matchStage, restStages)
              }
              .collect[Seq](limit, Cursor.FailOnError())

            (fetchResults, fetchCount, fetchMatchCount).mapN {
              case (results, count, matchCount) =>
                ResponseDepartures(
                  results.map(ResponseDeparture.build),
                  results.length,
                  totalDepartures = count,
                  totalMatched = Some(matchCount)
                )
            }
        }
    }

  // private def withLRNSearchQuery(
  //   lrn: String,
  //   pageSize: Option[Int],
  //   channelFilter: ChannelType,
  //   selector: JsObject,
  //   countSelector: JsObject
  // ): Future[ResponseDepartures] = {
  //   val lrnSelector = Json.obj("referenceNumber" -> Json.obj("$regex" -> lrn))
  //   val limit       = pageSize.map(Math.max(1, _)).getOrElse(appConfig.maxRowsReturned(channelFilter))

  //   val nextMessageId = Json.obj("nextMessageId" -> Json.obj("$size" -> "$messages"))

  //   val projection = DepartureWithoutMessages.projection ++ nextMessageId

  //   collection.flatMap {
  //     c =>
  //       val fetchCount = c.count(Some(countSelector))

  //       val fetchResults = c.aggregateWith[DepartureWithoutMessages](allowDiskUse = true) {
  //         _ =>
  //           import c.aggregationFramework._

  //           val initialFilter: PipelineOperator =
  //             Match(selector)

  //           val projected       = List[PipelineOperator](Project(projection))
  //           val sort            = List[PipelineOperator](Sort(Descending("lastUpdated")))
  //           val limited         = List[PipelineOperator](Limit(appConfig.maxRowsReturned(channelFilter)))
  //           val transformations = projected ++ sort ++ limited

  //           (initialFilter, transformations)
  //       }
  //       for {
  //         results <- fetchResults.collect[Seq](appConfig.maxRowsReturned(channelFilter), Cursor.FailOnError())
  //         count   <- fetchCount
  //       } yield {
  //         ResponseDepartures(results.map(ResponseDeparture.build), results.length, count)
  //     coll =>
  //       val fetchCount      = coll.count(Some(countSelector))
  //       val totalMatchCount = coll.count(Some(countSelector ++ lrnSelector))
  //       val lrnFilter       = selector ++ lrnSelector
  //       val fetchResults = coll
  //         .find(lrnFilter, Some(DepartureWithoutMessages.projection))
  //         .sort(Json.obj("lastUpdated" -> -1))
  //         .cursor[DepartureWithoutMessages]()
  //         .collect[Seq](limit, Cursor.FailOnError())

  //       (fetchCount, fetchResults, totalMatchCount).mapN {
  //         case (count, results, matchCount) =>
  //           ResponseDepartures(
  //             departures = results.map(ResponseDeparture.build),
  //             retrievedDepartures = results.length,
  //             totalDepartures = count,
  //             totalMatched = Some(matchCount)
  //           )
  //       }
  //   }
  // }

  // private def withPaginationSearchQuery(
  //   page: Option[Int],
  //   pageSize: Option[Int],
  //   channelFilter: ChannelType,
  //   selector: JsObject,
  //   countSelector: JsObject
  // ): Future[ResponseDepartures] = {

  //   val limit = pageSize.map(Math.max(1, _)).getOrElse(appConfig.maxRowsReturned(channelFilter))
  //   val skip  = Math.abs(page.getOrElse(1) - 1) * limit

  //   collection.flatMap {
  //     coll =>
  //       val fetchCount = coll.count(Some(countSelector))
  //       val fetchResults = coll
  //         .find(selector, Some(DepartureWithoutMessages.projection))
  //         .sort(Json.obj("lastUpdated" -> -1))
  //         .skip(skip)
  //         .cursor[DepartureWithoutMessages]()
  //         .collect[Seq](limit, Cursor.FailOnError())

  //       (fetchCount, fetchResults).mapN {
  //         case (count, results) =>
  //           ResponseDepartures(
  //             departures = results.map(ResponseDeparture.build),
  //             retrievedDepartures = results.length,
  //             totalDepartures = count
  //           )
  //       }
  //   }
  // }

  def updateDeparture[A](selector: DepartureSelector, modifier: A)(implicit ev: DepartureModifier[A]): Future[Try[Unit]] = {

    import models.DepartureModifier.toJson

    collection.flatMap {
      _.update(false)
        .one[JsObject, JsObject](Json.toJsObject(selector), modifier)
        .map {
          writeResult =>
            if (writeResult.n > 0)
              Success(())
            else
              writeResult.errmsg
                .map(
                  x => Failure(new Exception(x))
                )
                .getOrElse(Failure(new Exception("Unable to update message status")))
        }
    }
  }
}

object DepartureRepository {
  val collectionName = "departures"
}
