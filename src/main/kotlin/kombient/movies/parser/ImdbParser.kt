package kombient.movies.parser

import kombient.movies.repository.Rating
import kombient.movies.repository.RatingsRepository
import kombient.movies.tmdb.TmdbService
import kombient.slack.SlackService
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.persistence.EntityManagerFactory

@Component
@ConfigurationProperties(prefix = "imdb")
class ImdbParserConfig {
    var channel: String = ""
    var userconfig: Map<String, String> = HashMap()
    override fun toString(): String {
        return "ImdbParserConfig(channel='$channel', userconfig=$userconfig)"
    }

}

@Component
class ImdbParser(
        @Autowired private val imdbParserConfig: ImdbParserConfig,
        @Autowired private val entityManagerFactory: EntityManagerFactory,
        @Autowired private val ratingsRepository: RatingsRepository,
        @Autowired private val tmdbService: TmdbService,
        @Autowired private val slackService: SlackService
) {
    val MAX_BODY_SIZE_15M = 15_000_000

    @Scheduled(fixedRateString = "\${imdbparser.frequency}", initialDelayString = "\${imdbparser.delay}")
    fun parseImdb() {

        imdbParserConfig.userconfig.forEach { (username, userid) ->
            val get = Jsoup
                    .connect("http://www.imdb.com/user/$userid/ratings?ref_=nv_usr_rt_4")
                    .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/64.0.3282.167 Safari/537.36")
                    .maxBodySize(MAX_BODY_SIZE_15M)
                    .get()

            val movieBase = get.select("#ratings-container div.lister-item.mode-detail")

            val newRatings = ArrayList<Rating>()

            val userRatings = ratingsRepository.findAllByName(username)

            movieBase.forEach {

                val imdbId = it.select("div.lister-item-image").first().attr("data-tconst")
                val vote = it.select("div.lister-item-content div.ipl-rating-widget div.ipl-rating-star--other-user span.ipl-rating-star__rating").first().text()
                val date = it.select("div.lister-item-content div.ipl-rating-widget + p").first().text().removePrefix("Rated on ")

                val zonedDate = LocalDate.parse(date, DateTimeFormatter.ofPattern("dd MMM uuuu"))

                val ratingExists = userRatings.find { it.date == zonedDate && it.imdbId == imdbId && it.name == username && it.vote == vote.toInt() }

                if (ratingExists == null) {
                    newRatings.add(Rating(name = username, imdbId = imdbId, vote = vote.toInt(), date = zonedDate))
                }

            }

            saveMoviesInTheDatabase(newRatings, username)


        }
    }

    @Transactional
    fun saveMoviesInTheDatabase(newRatings: ArrayList<Rating>, username: String) {
        val entityManager = entityManagerFactory.createEntityManager()

        newRatings.forEach { entityManager.persist(it) }

        val titles = newRatings.map { tmdbService.findMovieByImdbId(it.imdbId).movie_results.first().title + "(${it.vote})" }.joinToString(separator = ", ") { it }
        
        if (newRatings.size > 0) {
            slackService.sendMessage(imdbParserConfig.channel, "$username voted: $titles")
        }
    }

}