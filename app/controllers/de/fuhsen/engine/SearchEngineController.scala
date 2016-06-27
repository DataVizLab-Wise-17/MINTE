/*
 * Copyright (C) 2016 EIS Uni-Bonn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers.de.fuhsen.engine

import java.util.{Calendar, UUID}
import com.typesafe.config.ConfigFactory
import controllers.de.fuhsen.FuhsenVocab
import org.apache.jena.query.{QueryExecutionFactory, QueryFactory}
import org.apache.jena.riot.Lang
import utils.dataintegration.RDFUtil
import scala.concurrent.Future
import javax.inject.Inject
import org.apache.jena.rdf.model.{Model, ModelFactory}
import play.Logger
import play.api.mvc.{Action, Controller}
import play.api.libs.ws.{WSClient, WSResponse}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

class SearchEngineController @Inject()(ws: WSClient) extends Controller {

  def search(uid: String, entityType: String) = Action.async { request =>

    Logger.info("Starting Search Engine Search : "+uid)

    GraphResultsCache.getModel(uid) match {
      case Some(model) =>

        if (getQueryDate(model) == null) {
          //Search results are not in storage, searching process starting

          //Adding query date property
          model.getResource(FuhsenVocab.SEARCH_URI + uid).addProperty(model.createProperty(FuhsenVocab.QUERY_DATE), Calendar.getInstance.getTime.toString)

          //Micro-task services executed
          val data = RDFUtil.modelToTripleString(model, Lang.TURTLE)
          val microtaskServer = ConfigFactory.load.getString("engine.microtask.url")
          val futureResponse: Future[WSResponse] = for {
            responseOne <- ws.url(microtaskServer+"/engine/api/queryprocessing?query=Diego").post(data)
            responseTwo <- ws.url(microtaskServer+"/engine/api/federatedquery?query=Diego").post(responseOne.body)
            responseThree <- ws.url(microtaskServer+"/engine/api/entitysummarization?query=Diego").post(responseTwo.body)
            responseFour <- ws.url(microtaskServer+"/engine/api/semanticranking?query=Diego").post(responseThree.body)
          } yield responseFour
          //action taken in case of failure
          futureResponse.recover {
            case e: Exception =>
              val exceptionData = Map("error" -> Seq(e.getMessage))
              //ws.url(exceptionUrl).post(exceptionData)
              Logger.error(exceptionData.toString())
          }

          futureResponse.map {
            r =>
              val finalModel = RDFUtil.rdfStringToModel(r.body, Lang.TURTLE)
              GraphResultsCache.saveModel(uid, finalModel)
              Logger.info("Search results stored in cache: "+uid)

              //Return sub model by type
              Ok(RDFUtil.modelToTripleString(getSubModel(entityType, finalModel), Lang.JSONLD))
          }
        }
        //Search results are already in storage
        else {
          Logger.info("Results are in cache.")
          Future.successful(Ok(RDFUtil.modelToTripleString(getSubModel(entityType, model), Lang.JSONLD)))
        }


      case None =>
        Future.successful(InternalServerError("Provided uid has not result model associated."))
    }


  }

  def startSession(query: String) = Action { request =>
    Logger.info("Starting Search Session with Query: " + query)

    val searchUid = UUID.randomUUID.toString()
    val searchURI = FuhsenVocab.SEARCH_URI + searchUid

    val model = ModelFactory.createDefaultModel()

    //Creating fs:Search resource
    model.createResource(searchURI)
      .addProperty(model.createProperty(FuhsenVocab.UID), searchUid)
      .addProperty(model.createProperty(FuhsenVocab.KEYWORD), query)
      //.addProperty(model.createProperty(FuhsenVocab.QUERY_DATE), Calendar.getInstance.getTime.toString)

    //Storing in session the new search unique identifier
    GraphResultsCache.saveModel(searchUid, model)
    Logger.info("Number of searches: "+GraphResultsCache.size)
    //request.session + ("SearchUid" -> searchUid)
    //cache.set(searchUid, model, 1.hours)

    Logger.info("Search Session started: "+searchUid)

    //Building json response
    val json: JsValue = Json.parse(s"""
        {
          "keyword" : "$query",
          "uid" : "$searchUid"
        }
        """)
    Ok(json)
  }

  def stopSession(searchUid: String) = Action { request =>
    Logger.info("Stopping Search Session: " + searchUid)

    GraphResultsCache.deleteModel(searchUid)
    Logger.info("Number of searches: "+GraphResultsCache.size)
    Ok

  }

  private def getSubModel(entityType :String, model :Model) : Model = {

    entityType match {
      case "person" =>
        val query = QueryFactory.create(
          s"""
             |PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
             |PREFIX fs: <http://vocab.lidakra.de/fuhsen#>
             |PREFIX foaf: <http://xmlns.com/foaf/0.1/>
             |PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
             |
             |CONSTRUCT   {
             |?p rdf:type foaf:Person .
             |?p fs:title ?name .
             |?p fs:image ?img .
             |?p fs:url ?url .
             |?p fs:source ?source .
             |?p fs:alias ?alias .
             |?p fs:location ?location .
             |?p fs:label ?label .
             |?p fs:comment ?comment .
             |?p foaf:gender ?gender .
             |?p fs:occupation ?occupation .
             |?p fs:birthday ?birthday .
             |?p fs:rank ?rank .
             |}
             |WHERE {
             |?p rdf:type foaf:Person .
             |?p foaf:name ?name .
             |?p fs:source ?source .
             |OPTIONAL { ?p foaf:img ?img } .
             |OPTIONAL { ?p fs:url ?url } .
             |OPTIONAL { ?p fs:alias ?alias } .
             |OPTIONAL { ?p fs:location ?location } .
             |OPTIONAL { ?p rdfs:label ?label } .
             |OPTIONAL { ?p rdfs:comment ?comment } .
             |OPTIONAL { ?p foaf:gender ?gender } .
             |OPTIONAL { ?p fs:occupation ?occupation } .
             |OPTIONAL { ?p fs:birthday ?birthday } .
             |?p fs:rank ?rank .
             |}
          """.stripMargin)
        QueryExecutionFactory.create(query, model).execConstruct()
      case "product" =>
        val query = QueryFactory.create(
          s"""
             |PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
             |PREFIX fs: <http://vocab.lidakra.de/fuhsen#>
             |PREFIX foaf: <http://xmlns.com/foaf/0.1/>
             |PREFIX gr: <http://purl.org/goodrelations/v1#>
             |
             |CONSTRUCT   {
             |?p rdf:type gr:ProductOrService .
             |?p fs:title ?description .
             |?p fs:image ?img .
             |?p fs:url ?url .
             |?p fs:location ?location .
             |?p fs:country ?country .
             |?p fs:priceLabel ?price .
             |?p fs:condition ?condition .
             |?p fs:source ?source .
             |}
             |WHERE {
             |?p rdf:type gr:ProductOrService .
             |?p gr:description ?description .
             |?p fs:source ?source .
             |OPTIONAL { ?p foaf:img ?img } .
             |OPTIONAL { ?p fs:url ?url } .
             |OPTIONAL { ?p fs:location ?location } .
             |OPTIONAL { ?p fs:country ?country } .
             |OPTIONAL { ?p fs:priceLabel ?price } .
             |OPTIONAL { ?p fs:condition ?condition } .
             |}
          """.stripMargin)
        QueryExecutionFactory.create(query, model).execConstruct()
      case "organization" =>
        val query = QueryFactory.create(
          s"""
             |PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
             |PREFIX fs: <http://vocab.lidakra.de/fuhsen#>
             |PREFIX foaf: <http://xmlns.com/foaf/0.1/>
             |PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
             |
             |CONSTRUCT   {
             |?p rdf:type foaf:Organization .
             |?p fs:title ?name .
             |?p fs:image ?img .
             |?p fs:url ?url .
             |?p fs:label ?label .
             |?p fs:comment ?comment .
             |?p fs:source ?source .
             |}
             |WHERE {
             |?p rdf:type foaf:Organization .
             |?p foaf:name ?name .
             |?p fs:source ?source .
             |OPTIONAL { ?p foaf:img ?img } .
             |OPTIONAL { ?p fs:url ?url } .
             |OPTIONAL { ?p rdfs:label ?label } .
             |OPTIONAL { ?p rdfs:comment ?comment } .
             |}
          """.stripMargin)
        QueryExecutionFactory.create(query, model).execConstruct()
      case "website" =>
        val query = QueryFactory.create(
          s"""
             |PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
             |PREFIX fs: <http://vocab.lidakra.de/fuhsen#>
             |PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
             |PREFIX foaf: <http://xmlns.com/foaf/0.1/>
             |
             |CONSTRUCT   {
             |?p rdf:type foaf:Document .
             |?p fs:title ?label .
             |?p fs:excerpt ?comment .
             |?p fs:url ?url .
             |?p fs:source ?source
             |}
             |WHERE {
             |?p rdf:type foaf:Document .
             |?p rdfs:label ?label .
             |OPTIONAL { ?p rdfs:comment ?comment } .
             |OPTIONAL { ?p fs:url ?url } .
             |OPTIONAL { ?p fs:source ?source } .
             |}
          """.stripMargin)
        QueryExecutionFactory.create(query, model).execConstruct()
    }

  }

  private def getQueryDate(model: Model): String = {

    val query = QueryFactory.create(
      s"""
         |PREFIX fs: <http://vocab.lidakra.de/fuhsen#>
         |SELECT ?queryDate WHERE {
         |?search fs:queryDate ?queryDate .
         |} limit 10
      """.stripMargin)
    val resultSet = QueryExecutionFactory.create(query, model).execSelect()

    if( resultSet.hasNext )
      resultSet.next.getLiteral("queryDate").getString
    else
      null
  }

}

