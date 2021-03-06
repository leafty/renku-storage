/*
 * Copyright 2017 - Swiss Data Science Center (SDSC)
 * A partnership between École Polytechnique Fédérale de Lausanne (EPFL) and
 * Eidgenössische Technische Hochschule Zürich (ETHZ).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers

import java.time.Instant
import java.util.UUID

import authorization.JWTVerifierProvider
import ch.datascience.service.security.{ TokenFilter, TokenFilterActionBuilder }
import ch.datascience.service.utils.ControllerWithBodyParseTolerantJson
import com.auth0.jwt.interfaces.DecodedJWT
import controllers.storageBackends.{ Backends, GitBackend }
import javax.inject.{ Inject, Singleton }
import models._
import models.persistence.DatabaseLayer
import play.api.Logger
import play.api.db.slick.HasDatabaseConfig
import play.api.libs.json._
import play.api.libs.streams.Accumulator
import play.api.libs.ws._
import play.api.mvc._
import slick.jdbc.JdbcProfile

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
/**
 * Created by jeberle on 25.04.17.
 */
@Singleton
class GitController @Inject() (
    config:                play.api.Configuration,
    jwtVerifier:           JWTVerifierProvider,
    tokenFilterAction:     TokenFilterActionBuilder,
    backends:              Backends,
    implicit val wsclient: WSClient,
    protected val dal:     DatabaseLayer,
    cc:                    ControllerComponents
) extends AbstractController( cc ) with ControllerWithBodyParseTolerantJson with HasDatabaseConfig[JdbcProfile] {

  override protected val dbConfig = dal.dbConfig
  import profile.api._

  lazy val logger: Logger = Logger( "application.GitController" )

  val host: String = config.get[String]( "renku_host" )
  val default_backend: String = config.get[String]( "lfs_default_backend" )

  implicit lazy val LFSBatchResponseFormat: OFormat[LFSBatchResponse] = LFSBatchResponse.format
  implicit lazy val LFSBatchResponseUpFormat: OFormat[LFSBatchResponseUp] = LFSBatchResponseUp.format

  implicit val ec: ExecutionContext = defaultExecutionContext

  def getRefs( id: String ) = tokenFilterAction( jwtVerifier.get ).async { implicit request =>

    val json = JsString( id )
    json.validate[UUID] match {
      case JsError( e ) => Future( BadRequest( JsError.toJson( e ) ) )
      case JsSuccess( uuid, _ ) =>
        db.run( dal.repositories.findByUUID( uuid ) ).flatMap {
          case Some( repo ) =>
            val backend = repo.backend
            backends.getBackend( backend.getOrElse( default_backend ) ) match {
              case Some( back ) =>
                back.asInstanceOf[GitBackend].getRefs( request, repo.path, request.userId )
              case None => Future.successful( BadRequest( s"The backend $backend is not enabled." ) )
            }
          case None => Future.successful( NotFound )
        }
    }
  }

  def uploadPack( id: String ) = EssentialAction { reqh =>
    TokenFilter( jwtVerifier.get, "" ).filter( reqh ) match {
      case Right( profile ) =>
        val json = JsString( id )
        val futur = json.validate[UUID] match {
          case JsError( e ) => Future( Accumulator.done( BadRequest( JsError.toJson( e ) ) ) )
          case JsSuccess( uuid, _ ) =>
            db.run( dal.repositories.findByUUID( uuid ) ).map {
              case Some( repo ) =>
                val backend = repo.backend
                backends.getBackend( backend.getOrElse( default_backend ) ) match {
                  case Some( back ) =>
                    back.asInstanceOf[GitBackend].upload( reqh, repo.path, "" ) //profile.getId )
                  case None => Accumulator.done( BadRequest( s"The backend $backend is not enabled." ) )
                }
              case None => Accumulator.done( NotFound )
            }
        }
        Await.result( futur, 10.seconds )
      case Left( res ) => Accumulator.done( res )
    }
  }

  def receivePack( id: String ) = EssentialAction { reqh =>
    TokenFilter( jwtVerifier.get, "" ).filter( reqh ) match {
      case Right( profile ) =>
        val json = JsString( id )
        val futur = json.validate[UUID] match {
          case JsError( e ) => Future( Accumulator.done( BadRequest( JsError.toJson( e ) ) ) )
          case JsSuccess( uuid, _ ) =>
            db.run( dal.repositories.findByUUID( uuid ) ).map {
              case Some( repo ) =>
                val backend = repo.backend
                backends.getBackend( backend.getOrElse( default_backend ) ) match {
                  case Some( back ) =>
                    back.asInstanceOf[GitBackend].receive( reqh, repo.path, profile.getId )
                  case None => Accumulator.done( BadRequest( s"The backend $backend is not enabled." ) )
                }
              case None => Accumulator.done( NotFound )
            }
        }
        Await.result( futur, 10.seconds )
      case Left( res ) => Accumulator.done( res )
    }
  }

  def lfsBatch( id: String ): Action[LFSBatchRequest] = Action.async( bodyParseJson[LFSBatchRequest]( LFSBatchRequest.format ) ) { implicit request =>
    val tokenOrError: Either[Result, DecodedJWT] = TokenFilter( jwtVerifier.get, "" ).filter( request )
    val json = JsString( id )
    json.validate[UUID] match {
      case JsError( e ) => Future( BadRequest( JsError.toJson( e ) ) )
      case JsSuccess( uuid, _ ) =>
        if ( request.body.operation == "download" ) {
          lfsBatchDownload( uuid, request, tokenOrError )
        }
        else {
          tokenOrError match {
            case Right( token ) => lfsBatchUpload( uuid, request, token )
            case Left( error )  => Future.successful( error )
          }
        }
    }
  }

  private def lfsBatchDownload( uuid: UUID, request: Request[LFSBatchRequest], tokenOrError: Either[Result, DecodedJWT] ) = {
    val objects = request.body.objects.map( lfsObject => {
      db.run( dal.fileObjectRepositories.listByFileObjectHash( lfsObject.oid ) ).map( _.headOption.map {
        case ( repository, _, fileObject ) => {
          val head = Map( "Content-Hash" -> lfsObject.oid ) ++ tokenOrError.fold( _ => Map.empty[String, String], t => Map( "Authorization" -> t.getToken ) )
          LFSObjectResponse( lfsObject.oid, lfsObject.size, true, Some( LFSDownload( host + "/api/storage/repo/" + repository.uuid + "/object/" + fileObject.uuid, head, 600 ) ) )
        }
      } )
    } )
    Future.sequence( objects ).map( l => Ok( Json.toJson( LFSBatchResponse( request.body.transfers, l.filter( _.nonEmpty ).map( _.get ) ) ) ) )
  }

  private def lfsBatchUpload( uuid: UUID, request: Request[LFSBatchRequest], token: DecodedJWT ) = {

    db.run( dal.repositories.findByUUID( uuid ) ).flatMap {
      case Some( repo ) => {
        val new_uuid = UUID.randomUUID()
        if ( repo.lfs_store.isEmpty ) {
          backends.getBackend( default_backend ) match {
            case Some( back ) => {
              back.createRepo( Repository( new_uuid, None, "automatically created bucket for LFS of " + uuid.toString, "", Some( default_backend ), None, None, None ) ).map(
                i =>
                  i.map( iid => {
                    val rep = Repository( new_uuid, Some( iid ), "automatically created bucket for LFS of " + uuid.toString, "", Some( default_backend ), Some( Instant.now() ), Some( UUID.fromString( token.getId ) ), None )
                    val action = for {
                      ire <- dal.repositories.insert( rep )
                      ure <- dal.repositories.update( Repository( repo.uuid, repo.iid, repo.description, repo.path, repo.backend, repo.created, repo.owner, Some( new_uuid ) ) )
                    } yield ( ire, ure )
                    db.run( action.transactionally )
                  } )
              )
            }
            case None => {}
          }
        }
        val objects = request.body.objects.map( lfsObject =>
          db.run( dal.fileObjects.findByHash( lfsObject.oid ) ) map {
            case Some( obj ) => Some( LFSObjectResponseUp( lfsObject.oid, lfsObject.size, true, None ) )
            case None        => Some( LFSObjectResponseUp( lfsObject.oid, lfsObject.size, true, Some( LFSUpload( host + "/api/storage/repo/" + repo.lfs_store.getOrElse( new_uuid ) + "/object/" + UUID.randomUUID(), Map( "Authorization" -> token.getToken, "Content-Hash" -> lfsObject.oid ), 600 ) ) ) )
          } )
        Future.sequence( objects ).map( l => Ok( Json.toJson( LFSBatchResponseUp( request.body.transfers, l.filter( _.nonEmpty ).map( _.get ) ) ) ) )
      }
      case None => Future.successful( NotFound )
    }
  }

}

