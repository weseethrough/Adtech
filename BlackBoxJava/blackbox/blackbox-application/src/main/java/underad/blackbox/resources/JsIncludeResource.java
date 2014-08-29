package underad.blackbox.resources;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;

import lombok.RequiredArgsConstructor;

import org.joda.time.DateTime;

import underad.blackbox.BlackboxConfiguration;
import underad.blackbox.core.AdvertMetadata;
import underad.blackbox.core.util.Crypto;
import underad.blackbox.jdbi.AdAugmentDao;
import underad.blackbox.jdbi.PublisherPasswordDao;
import underad.blackbox.views.JsIncludeView;

import com.codahale.metrics.annotation.Timed;
import com.google.common.collect.ImmutableList;

// Path naming gives a clue as to content it provides. A little misleading as it doesn't suggest code gen...
@Path("/include.js")
@Produces("application/javascript")
@RequiredArgsConstructor
public class JsIncludeResource {
	
	private final BlackboxConfiguration configuration;
	private final AdAugmentDao adAugmentDao;
	private final PublisherPasswordDao publisherKeyDao;
	
	private URI getReconstructionUrl(long id) {
		try {
			// TODO need to use UriInfo.getBaseUriBuilder() as well I think
			URI reconstructRelUrl = UriBuilder.fromResource(ReconstructResource.class).path("?id=" + id).build();
			URL hostUrl = configuration.getHostUrl();
			return new URL(hostUrl.getProtocol(), hostUrl.getHost(), hostUrl.getPort(),
					reconstructRelUrl.getPath(), null).toURI();
		} catch (MalformedURLException | URISyntaxException e) {
			throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR);
		}
	}
	
	/**
	 * Returns JavaScript code required to:
	 * 
	 * 1. Detect whether the adverts in the page have been blocked,
	 * 2. Retrieve fresh advert HTML (via ReconstructResource) to replace blocked adverts, including references to new,
	 * 'underad'-style 'adblock-proof' advert resources (images, JS, etc).
	 * 
	 * @param url The page serving the ads, i.e. that will link to this include.
	 * @param publisherUnixTimeSecs Unix time on publisher's server at point of sending request, in seconds.
	 * @return AdBlock-defeating JavaScript.
	 */
	@GET
	@Timed
	public JsIncludeView getInclude(@QueryParam("url") String url, @QueryParam("unixtime") long publisherUnixTimeSecs) {
	    try {
			new URI(url); // slightly clumsy validation... TODO can this be replaced with DW's validation stuff?
		} catch (URISyntaxException e) {
			throw new WebApplicationException(e, Status.BAD_REQUEST);
		}
		// DateTime(long) expects millis since Unix epoch, not seconds.
		long publisherUnixTimeMillis = publisherUnixTimeSecs * 1000;
		DateTime publisherTs = new DateTime(publisherUnixTimeMillis);
	    
	    // Determine what adverts need obfuscating.
		List<AdvertMetadata> advertMetadata = ImmutableList.copyOf(adAugmentDao.getAdverts(url, publisherTs));
		if (advertMetadata.isEmpty()) 
			// probably means that the URL isn't owned by one of our publisher clients at present. That or config error.
			throw new WebApplicationException(Status.BAD_REQUEST);
		
		// Get appropriate key for encrypting paths.
		String key = publisherKeyDao.getPassword(url, publisherTs);
		
		for (AdvertMetadata adMeta : advertMetadata) {
			String reconstructUrl = getReconstructionUrl(adMeta.getId()).toString();
			// The only URL we need to encrypt in the blackbox is the reconstruct URL that provides adblock-proof ad
			// HTML.
			String reconstructUrlCipherText = Crypto.encrypt(
					key, publisherUnixTimeMillis, reconstructUrl);
			adMeta.setEncryptedReconstructUrl(reconstructUrlCipherText);
		}
		
		JsIncludeView view = new JsIncludeView(advertMetadata, publisherUnixTimeSecs);
		
		// TODO how can we minify the JavaScript that comes out of Mustache? Quite important for "security through
		// obscurity" reasons... maybe with a Jersey interceptor?
		// https://jersey.java.net/documentation/latest/filters-and-interceptors.html#d0e8333
		// Unfortunately this is available from Jersey 2.x, and DropWizard is currently (2014-08-27) on 1.x.
		// Easiest answer: revisit once DW goes over to 2.x. This might also be helpful:
		// http://stackoverflow.com/questions/19785001/custom-method-annotation-using-jerseys-abstracthttpcontextinjectable-not-workin
		// Question posed here:
		// http://stackoverflow.com/questions/25546778/intercepting-http-response-body-in-dropwizard-0-7-0-jersey-1-x
		return view;
	}
}
