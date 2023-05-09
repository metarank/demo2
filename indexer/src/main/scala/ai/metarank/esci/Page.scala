package ai.metarank.esci

import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Decoder, DecodingFailure, Encoder, Json, JsonObject}

sealed trait Page {
  def asin: String
  def locale: String
  def template: String
}

object Page {
  case class BrokenPage(asin: String, locale: String, error: String, template: String = "") extends Page

  sealed trait ContentPage extends Page {
    def title: String
    def stars: String
    def ratings: String
    def category: List[String]
    def reviews: List[Review]
    def desc: String
    def attr: Map[String, String]
    def formats: Map[String, String]
    def template: String
    def price: String

    val starsPattern = "([0-9])\\.([0-9]) out of 5 stars".r

    def parseStars(stars: String) = stars match {
      case starsPattern(a, b) =>
        (a.toIntOption, b.toIntOption) match {
          case (Some(i), Some(m)) => Some(i.toDouble + m.toDouble / 10.0)
          case _ => None
        }
      case _ => None
    }

    val ratingPattern1 = "([0-9]+) ratings".r
    val ratingPattern2 = "([0-9]+),([0-9]+) ratings".r

    def parseRatings(r: String) = r match {
      case ratingPattern1(a) => a.toIntOption
      case ratingPattern2(a, b) =>
        (a.toIntOption, b.toIntOption) match {
          case (Some(i), Some(m)) => Some(i * 1000 + m)
          case _ => None
        }
      case _ => None
    }
  }
  case class Review(stars: String, title: String, date: String, text: String)

  case class BookPage(
      locale: String,
      asin: String,
      template: String,
      title: String,
      subtitle: String,
      author: String,
      stars: String,
      ratings: String,
      price: String,
      category: List[String],
      reviews: List[Review],
      desc: String,
      attr: Map[String, String],
      formats: Map[String, String],
      review: String,
      img: String
  ) extends ContentPage

  case class ProductPage(
      locale: String,
      asin: String,
      title: String,
      stars: String,
      ratings: String,
      category: List[String],
      attrs: Map[String, String],
      bullets: List[String],
      description: String,
      info: Map[String, String],
      reviews: List[Review],
      price: String,
      formats: Map[String, String],
      template: String,
      image: String
  ) extends ContentPage {
    override def desc: String              = description
    override def attr: Map[String, String] = attrs

  }

  implicit val brokenCodec: Codec[BrokenPage] = deriveCodec
  implicit val bookCodec: Codec[BookPage]     = deriveCodec
  implicit val reviewCodec: Codec[Review]     = deriveCodec
  implicit val prodPage: Codec[ProductPage]   = deriveCodec

  implicit val pageEncoder: Encoder[Page] = Encoder.instance {
    case p: BookPage    => bookCodec(p).deepMerge(tpe("book"))
    case p: BrokenPage  => brokenCodec(p).deepMerge(tpe("error"))
    case p: ProductPage => prodPage(p).deepMerge(tpe("product"))
  }

  implicit val pageDecoder: Decoder[Page] = Decoder.instance(c =>
    for {
      tpe <- c.downField("type").as[String]
      page <- tpe match {
        case "book"    => bookCodec.tryDecode(c)
        case "error"   => brokenCodec.tryDecode(c)
        case "product" => prodPage.tryDecode(c)
        case _         => Left(DecodingFailure("nope", c.history))
      }
    } yield {
      page
    }
  )

  def tpe(t: String) = Json.fromJsonObject(JsonObject.fromMap(Map("type" -> Json.fromString(t))))
}
