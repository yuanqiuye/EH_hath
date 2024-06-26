/*

Copyright 2008-2020 E-Hentai.org
https://forums.e-hentai.org/
ehentai@gmail.com

This file is part of Hentai@Home.

Hentai@Home is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Hentai@Home is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Hentai@Home.  If not, see <http://www.gnu.org/licenses/>.

*/

package hath.base;

import java.util.Date;
import java.util.TimeZone;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.net.InetAddress;
import java.net.Socket;
import java.lang.Thread;
import java.lang.StringBuilder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.regex.Pattern;

public class HTTPMetricsSession implements Runnable {

	public static final String CRLF = "\r\n";

	private static final Pattern getheadPattern = Pattern.compile("^((GET)|(HEAD)).*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private Socket socket;
	private int connId;
	private Thread myThread;
	private HTTPMetricsResponse hmr;

	public HTTPMetricsSession(Socket socket, int connId) {
		this.socket = socket;
		this.connId = connId;
	}

	public void handleSession() {
		myThread = new Thread(this);
		myThread.start();
		myThread.setName("HTTPMetricsSession");
	}

	private void connectionFinished() {
		if(hmr != null) {
			hmr.requestCompleted();
		}
	}

	public void run() {
		// why are we back to input/output streams? because java has no SSLSocketChannel, using them with SSLEngine is stupidly complex, and all the middleware libraries for SSL over channels are either broken, outdated, or require a major code rewrite
		// may switch back to channels in the future if a decent library materializes, or I can be arsed to learn SSLEngine and implementing it does not require a major rewrite
		BufferedReader reader = null;
		DataOutputStream writer = null;
		HTTPResponseProcessor hpc = null;
		String info = this.toString() + " ";

		try {
			socket.setSoTimeout(10000);
			
			reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			writer = new DataOutputStream(socket.getOutputStream());

			// read the header and parse the request - this will also update the response code and initialize the proper response processor
			String request = null;

			// ignore every single line except for the request one. we SSL now, so if there is no end-of-line, just wait for the timeout
			do {
				String read = reader.readLine();

				if(read != null) {

					if(getheadPattern.matcher(read).matches()) {
						request = read.substring(0, Math.min(1000, read.length()));
					}
					else if(read.isEmpty()) {
						break;
					}
				}
				else {
					break;
				}
			} while(true);
			
			hmr = new HTTPMetricsResponse(this);
			hmr.parseRequest(request);

			// get the status code and response processor - in case of an error, this will be a text type with the error message
			hpc = hmr.getHTTPResponseProcessor();
			int statusCode = hmr.getResponseStatusCode();
			int contentLength = hpc.getContentLength();

			// we'll create a new date formatter for each session instead of synchronizing on a shared formatter. (sdf is not thread-safe)
			SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", java.util.Locale.US);
			sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

			// build the header
			StringBuilder header = new StringBuilder(300);
			header.append(getHTTPStatusHeader(statusCode));
			header.append(hpc.getHeader());
			header.append("Date: " + sdf.format(new Date()) + " GMT" + CRLF);
			header.append("Server: Genetic Lifeform and Distributed Open Server " + Settings.CLIENT_VERSION + CRLF);
			header.append("Connection: close" + CRLF);
			header.append("Content-Type: text/plain" + CRLF);

			if(contentLength > 0) {
				header.append("Cache-Control: public, max-age=31536000" + CRLF);
				header.append("Content-Length: " + contentLength + CRLF);
			}

			header.append(CRLF);

			// write the header to the socket
			byte[] headerBytes = header.toString().getBytes(Charset.forName("ISO-8859-1"));

			if(request != null && contentLength > 0) {
				try {
					// buffer size might be limited by OS. for linux, check net.core.wmem_max
					int bufferSize = (int) Math.min(contentLength + headerBytes.length + 32, Math.min(Settings.isUseLessMemory() ? 131072 : 524288, Math.round(0.2 * Settings.getThrottleBytesPerSec())));
					socket.setSendBufferSize(bufferSize);
					//Out.debug("Socket size for " + connId + " is now " + socket.getSendBufferSize() + " (requested " + bufferSize + ")");
				}
				catch (Exception e) {
					Out.info(e.getMessage());
				}
			}

			writer.write(headerBytes, 0, headerBytes.length);
			
			//Out.debug("Wrote " +  headerBytes.length + " header bytes to socket for connId=" + connId + " with contentLength=" + contentLength);

			if(hmr.isRequestHeadOnly()) {
				// if this is a HEAD request, we are done
				writer.flush();

				info += "Code=" + statusCode + " ";
				Out.info(info + (request == null ? "Invalid Request" : request));
			}
			else {
				// if this is a GET request, process the body if we have one
				info += "Code=" + statusCode + " Bytes=" + String.format("%1$-8s", contentLength) + " ";

				if(request != null) {
					// skip the startup message for error requests
					Out.info(info + request);
				}

				long startTime = System.currentTimeMillis();

				if(contentLength > 0) {
					int writtenBytes = 0;
					int lastWriteLen = 0;
					
					// bytebuffers returned by getPreparedTCPBuffer should never have a remaining() larger than Settings.TCP_PACKET_SIZE. if that happens due to some bug, we will hit an IndexOutOfBounds exception during the get below
					byte[] buffer = new byte[Settings.TCP_PACKET_SIZE];

					while(writtenBytes < contentLength) {
						ByteBuffer tcpBuffer = hpc.getPreparedTCPBuffer();
						lastWriteLen = tcpBuffer.remaining();
						
						tcpBuffer.get(buffer, 0, lastWriteLen);
						writer.write(buffer, 0, lastWriteLen);
						writtenBytes += lastWriteLen;
						
						//Out.debug("Wrote " + lastWriteLen + " content bytes to socket for connId=" + connId + " with contentLength=" + contentLength);
					}
				}

				writer.flush();

				// while the outputstream is flushed and empty, the bytes may not have made it further than the OS network buffers, so the time calculated here is approximate at best and widely misleading at worst, especially if the BWM is disabled
				long sendTime = System.currentTimeMillis() - startTime;
				DecimalFormat df = new DecimalFormat("0.00");
				Out.debug(info + "Finished processing request in " + df.format(sendTime / 1000.0) + " seconds" + (sendTime >= 10 ? " (" + df.format(contentLength / (float) sendTime) + " KB/s)" : ""));
			}
		}
		catch(Exception e) {
			Out.debug(info + "The connection was interrupted or closed by the remote host.");
			Out.debug(e == null ? "(no exception)" : e.getMessage());
			//e.printStackTrace();
		}
		finally {
			if(hpc != null) {
				hpc.cleanup();
			}

			try { reader.close(); writer.close(); } catch(Exception e) {}
			try { socket.close(); } catch(Exception e) {}
		}

		connectionFinished();
	}

	private String getHTTPStatusHeader(int statuscode) {
		switch(statuscode) {
			case 200: return "HTTP/1.1 200 OK" + CRLF;
			case 301: return "HTTP/1.1 301 Moved Permanently" + CRLF;
			case 400: return "HTTP/1.1 400 Bad Request" + CRLF;
			case 403: return "HTTP/1.1 403 Permission Denied" + CRLF;
			case 404: return "HTTP/1.1 404 Not Found" + CRLF;
			case 405: return "HTTP/1.1 405 Method Not Allowed" + CRLF;
			case 418: return "HTTP/1.1 418 I'm a teapot" + CRLF;
			case 501: return "HTTP/1.1 501 Not Implemented" + CRLF;
			case 502: return "HTTP/1.1 502 Bad Gateway" + CRLF;
			default: return "HTTP/1.1 500 Internal Server Error" + CRLF;
		}
	}
	
	public void forceCloseSocket() {
		try {
			if(!socket.isClosed()) {
				Out.debug("Closing socket for session " + connId);
				socket.close();
				Out.debug("Closed socket for session " + connId);
			}
		} catch(Exception e) {
			Out.debug(e.toString());
		}
	}

	// accessors

	public InetAddress getSocketInetAddress() {
		return socket.getInetAddress();
	}

	public String toString() {
		return "{" + connId + String.format("%1$-17s", getSocketInetAddress().toString() + "}");
	}

}
