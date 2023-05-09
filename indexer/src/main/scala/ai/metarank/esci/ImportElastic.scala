package ai.metarank.esci

import ai.metarank.esci.Page.{BookPage, ProductPage}
import cats.effect.kernel.Resource
import cats.effect.{ExitCode, IO, IOApp, Ref}
import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import com.github.luben.zstd.ZstdInputStream
import org.apache.http.HttpHost
import org.elasticsearch.client.RestClient
import fs2.Stream
import fs2.io.readInputStream
import io.circe.Codec
import io.circe.syntax._
import io.circe.parser._
import io.circe.generic.semiauto._
import cats.implicits._
import co.elastic.clients.elasticsearch._types.mapping.{
  DenseVectorIndexOptions,
  DenseVectorProperty,
  FloatNumberProperty,
  IntegerNumberProperty,
  Property,
  SourceField,
  TextProperty,
  TypeMapping
}
import co.elastic.clients.elasticsearch.core.bulk.{BulkOperation, IndexOperation}
import co.elastic.clients.elasticsearch.indices.{CreateIndexRequest, CreateIndexResponse}
import co.elastic.clients.util.{BinaryData, ContentType}

import scala.concurrent.duration._
import java.io.{BufferedInputStream, File, FileInputStream}

object ImportElastic extends IOApp {
  val esPattern = "([0-9\\.a-zA-Z]+):([0-9]+)".r

  case class EsciDocument(
      asin: String,
      title: String,
      desc: String,
      bullets: String,
      image: String,
//      review_cnt: Int,
//      review_score: Double,
//      price: String,
      emb_minilm: Array[Float],
      emb_minilm_ft: Array[Float]
  )
  implicit val esciDocCodec: Codec[EsciDocument] = deriveCodec

  override def run(args: List[String]): IO[ExitCode] = args match {
    case esPattern(host, port) :: esciPath :: embPath :: Nil =>
      createClient(host, port.toInt).use(client =>
        for {
          _ <- createIndex(client)
          embs <- List(
            readEmbeddingsFile(embPath + "/minilm.csv", 384),
            readEmbeddingsFile(embPath + "/minilm_ft.csv", 384)
          ).parSequence
          _ <- writeData(client, esciPath, embs(0), embs(1))
        } yield {
          ExitCode.Success
        }
      )

    case _ => IO.raiseError(new Exception("wrong args"))
  }

  def createClient(host: String, port: Int) = for {
    http      <- Resource.make(IO(RestClient.builder(new HttpHost(host, port)).build()))(c => IO(c.close()))
    transport <- Resource.make(IO(new RestClientTransport(http, new JacksonJsonpMapper())))(t => IO(t.close()))
    client    <- Resource.liftK(IO(new ElasticsearchClient(transport)))
  } yield {
    client
  }

  val empty384 = Array.fill(384)(1.0f)

  def writeData(
      client: ElasticsearchClient,
      esciPath: String,
      mlm: Map[String, Array[Float]],
      mlmft: Map[String, Array[Float]]
  ): IO[Unit] = for {
    cnt <- Ref.of[IO, Int](0)
    _ <- readInputStream[IO](IO(readEsciStream(esciPath)), 1024000)
      .through(fs2.text.utf8.decode)
      .through(fs2.text.lines)
      .filter(_.nonEmpty)
      .parEvalMapUnordered(8)(line => IO.fromEither(decode[Page](line)))
      .collect {
        case book: BookPage =>
          EsciDocument(
            asin = book.asin,
            title = book.title,
            desc = book.desc,
            bullets = book.review,
            image = book.img,
            emb_minilm = mlm.getOrElse(book.asin, empty384),
            emb_minilm_ft = mlmft.getOrElse(book.asin, empty384)
//            review_cnt = book.parseRatings(book.ratings).getOrElse(0),
//            review_score = book.parseStars(book.stars).getOrElse(0.0),
//            price = book.price
          )
        case prod: ProductPage =>
          EsciDocument(
            asin = prod.asin,
            title = prod.title,
            desc = prod.desc,
            bullets = prod.bullets.mkString(" "),
            image = prod.image,
            emb_minilm = mlm.getOrElse(prod.asin, empty384),
            emb_minilm_ft = mlmft.getOrElse(prod.asin, empty384)
//            review_cnt = prod.parseRatings(prod.ratings).getOrElse(0),
//            review_score = prod.parseStars(prod.stars).getOrElse(0.0),
//            price = prod.price
          )
      }
      .map(doc => doc.copy())
      .groupWithin(1024, 1.second)
      .evalMap(batch =>
        for {
          _ <- writeBatch(client, batch.toList)
          c <- cnt.updateAndGet(_ + batch.size)
          _ <- IO(println(s"wrote $c docs"))
        } yield {}
      )
      .compile
      .drain

  } yield {}

  def createIndex(client: ElasticsearchClient): IO[CreateIndexResponse] = IO {
    val text      = new TextProperty.Builder().analyzer("english").store(true).build();
    val store     = new TextProperty.Builder().index(false).store(true).build();
    val vector384 = new DenseVectorProperty.Builder().index(true).dims(384).similarity("cosine").build()
    val int       = new IntegerNumberProperty.Builder().store(true).build()
    val float     = new FloatNumberProperty.Builder().store(true).build()
    val mapping = new TypeMapping.Builder()
      .properties("title", new Property.Builder().text(text).build())
      .properties("desc", new Property.Builder().text(text).build())
      .properties("bullets", new Property.Builder().text(text).build())
      .properties("asin", new Property.Builder().text(store).build())
      .properties("image", new Property.Builder().text(store).build())
//      .properties("price", new Property.Builder().text(store).build())
//      .properties("review_count", new Property.Builder().integer(int).build())
//      .properties("review_score", new Property.Builder().float_(float).build())
      .properties("emb_minilm", new Property.Builder().denseVector(vector384).build())
      .properties("emb_minilm_ft", new Property.Builder().denseVector(vector384).build())
      .source(new SourceField.Builder().enabled(false).build())
      .build()
    val request = new CreateIndexRequest.Builder().index("esci").mappings(mapping).build()

    client.indices().create(request);
  }

  def writeBatch(client: ElasticsearchClient, batch: List[EsciDocument]): IO[Unit] = for {
    builder <- IO(new BulkRequest.Builder())
    _ <- batch.traverse(doc =>
      IO(
        builder
          .operations(
            new BulkOperation.Builder()
              .index(
                new IndexOperation.Builder[BinaryData]()
                  .index("esci")
                  .id(doc.asin)
                  .document(BinaryData.of(doc.asJson.noSpaces.getBytes(), ContentType.APPLICATION_JSON))
                  .build()
              )
              .build()
          )
      )
    )
    response <- IO(client.bulk(builder.build()))
    _        <- IO.whenA(response.errors())(IO.raiseError(new Exception(s"cannot write batch: $response")))
  } yield {}

  def readEsciStream(path: String) = {
    new BufferedInputStream(new ZstdInputStream(new FileInputStream(new File(path))), 1024000)
  }

  def readEmbeddingsFile(path: String, dim: Int): IO[Map[String, Array[Float]]] =
    CSVStream
      .fromFile(path, ',', 0)
      .evalMapChunk(line =>
        if (line.length == dim + 1) {
          IO {
            val asin = line(0)
            val emb  = new Array[Float](dim)
            var i    = 1
            while (i < dim + 1) {
              val flt = line(i).toFloat
              emb(i - 1) = flt
              i += 1
            }
            asin -> emb
          }
        } else {
          IO.raiseError(new Exception("dim mismatch"))
        }
      )
      .compile
      .toList
      .map(_.toMap)
      .flatTap(_ => IO(println(s"done importing $path")))
}
