/*
 * Copyright 2014 Gerrit Meinders
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package photodate;

import java.io.*;
import java.nio.charset.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

import com.drew.imaging.*;
import com.drew.metadata.*;
import com.drew.metadata.exif.*;

/**
 * FIXME Need comment
 *
 * @author Gerrit Meinders
 */
public class PhotoDate
{

	private long _dateOffset;

	private final Pattern _exifDatePattern = Pattern.compile( "[0-9]{4}:[0-9]{2}:[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}" );

	private final DateFormat _exitDateFormat = new SimpleDateFormat( "yyyy:MM:dd HH:mm:ss" );

	private final DateFormat _prefixDateFormat = new SimpleDateFormat( "'IMG_'yyyyMMdd'_'HHmmss" );

	private static final Pattern OFFSET_PATTERN = Pattern.compile( "([+-]?[0-9]+)([dhms])?" );

	private boolean _datePrefix;

	/**
	 * Run application.
	 *
	 * @param   args    Command-line arguments.
	 */
	public static void main( final String[] args )
	{
		List<String> arguments = new ArrayList<String>( Arrays.asList( args ) );

		if ( arguments.isEmpty() )
		{
			showHelp();
			System.exit( 1 );
		}

		PhotoDate photoDate = new PhotoDate();

		for ( Iterator<String> iterator = arguments.iterator(); iterator.hasNext(); )
		{
			String arg = iterator.next();
			if ( arg.startsWith( "-" ) )
			{
				if ( "--offset".equals( arg ) )
				{
					iterator.remove();

					String offsetValue = iterator.next();
					iterator.remove();

					Matcher matcher = OFFSET_PATTERN.matcher( offsetValue );
					if ( !matcher.matches() )
					{
						System.err.println( "Invalid offset: " + offsetValue );
						showHelp();
						System.exit( 1 );
					}

					long offset = Long.valueOf( matcher.group( 1 ) );
					String unit = matcher.group( 2 );
					if ( unit != null )
					{
						switch ( unit.charAt( 0 ) )
						{
							case 'd':
								offset = TimeUnit.DAYS.toMillis( offset );
								break;
							case 'h':
								offset = TimeUnit.HOURS.toMillis( offset );
								break;
							case 'm':
								offset = TimeUnit.MINUTES.toMillis( offset );
								break;
							case 's':
								offset = TimeUnit.SECONDS.toMillis( offset );
								break;
						}
					}

					photoDate.setDateOffset( offset );
				}
				else if ( "--date-prefix".equals( arg ) )
				{
					iterator.remove();
					photoDate.setDatePrefix( true );
				}
			}
		}

		for ( String path : arguments )
		{
			File file = new File( path );
			photoDate.processFiles( file );
		}
	}

	private void processFiles( File fileOrDirectory )
	{
		if ( fileOrDirectory.isDirectory() )
		{
			for ( final File file : fileOrDirectory.listFiles() )
			{
				processFiles( file );
			}
		}
		else
		{
			processFile( fileOrDirectory );
		}
	}

	private void processFile( File file )
	{
		try
		{
			Metadata metadata = ImageMetadataReader.readMetadata( file );

			Date dateTime = null;
			Date dateDigitized = null;
			Date dateOriginal = null;

			ExifIFD0Directory exifIFD0Directory = metadata.getDirectory( ExifIFD0Directory.class );
			if ( exifIFD0Directory != null )
			{
				dateTime = exifIFD0Directory.getDate( ExifIFD0Directory.TAG_DATETIME );
			}

			ExifSubIFDDirectory exifSubIFDDirectory = metadata.getDirectory( ExifSubIFDDirectory.class );
			if ( exifSubIFDDirectory != null )
			{
				dateDigitized = exifSubIFDDirectory.getDate( ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED );
				dateOriginal = exifSubIFDDirectory.getDate( ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL );
			}

			Charset charset = Charset.forName( "ISO-8859-1" );

			Date originalDate = ( dateOriginal != null ) ? dateOriginal : ( dateDigitized != null ) ? dateDigitized : dateTime;
			if ( originalDate != null )
			{
				Date adjustedDate = applyDateOffset( originalDate );
				String formattedAdjustedDate = _exitDateFormat.format( adjustedDate );
				byte[] binaryAdjustedDate = formattedAdjustedDate.getBytes( charset );

				InputStreamReader reader = new InputStreamReader( new FileInputStream( file ), charset );
				StringBuilder source = new StringBuilder();
				try
				{
					char[] buffer = new char[ 0x1000 ];
					for ( int read = reader.read( buffer ); read != -1; read = reader.read( buffer ) )
					{
						source.append( buffer, 0, read );
					}

					if ( file.length() != source.length() )
					{
						throw new AssertionError( "Failed to read binary data. " + file.length() + " != " + source.length() );
					}
				}
				finally
				{
					reader.close();
				}

				RandomAccessFile randomAccessFile = new RandomAccessFile( file, "rw" );

				try
				{
					Matcher matcher = _exifDatePattern.matcher( source );
					try
					{
						while ( matcher.find() )
						{
							String formattedOriginalDate = matcher.group( 0 );

							Date parsedDate = _exitDateFormat.parse( formattedOriginalDate );
							if ( parsedDate.equals( dateTime ) || parsedDate.equals( dateDigitized ) || parsedDate.equals( dateOriginal ) )
							{
								randomAccessFile.seek( matcher.start() );
								byte[] buffer = new byte[ matcher.end() - matcher.start() ];
								randomAccessFile.readFully( buffer );

								String checkDate = new String( buffer, charset );
								if ( !checkDate.equals( formattedOriginalDate ) )
								{
									throw new AssertionError( "Failed to read binary data. " + formattedOriginalDate + " != " + checkDate );
								}

								randomAccessFile.seek( matcher.start() );
								randomAccessFile.write( binaryAdjustedDate );

								System.out.println( file + ": " + formattedOriginalDate + " -> " + formattedAdjustedDate );
							}
						}
					}
					catch ( ParseException e )
					{
						System.err.println( file + ": Failed to parse date: " + e.getMessage() );
					}
				}
				finally
				{
					randomAccessFile.close();
				}

				if ( !file.setLastModified( adjustedDate.getTime() ) )
				{
					System.err.println( file + ": Failed to set last modified time." );
				}



				if ( _datePrefix )
				{
					final String newName = _prefixDateFormat.format( adjustedDate ) + ".JPG";
					System.out.println( " - renamed to " + newName );
					file.renameTo( new File( file.getParentFile(), newName ) );
				}
			}
			else
			{
				System.err.println( file + ": Failed to determine date." );
			}
		}
		catch ( ImageProcessingException e )
		{
			System.err.println( file + ": Failed to read metadata." );
		}
		catch ( IOException e )
		{
			System.err.println( file + ": Failed to read: " + e.getMessage() );
		}
	}

	private Date applyDateOffset( Date dateTime )
	{
		return new Date( dateTime.getTime() + _dateOffset );
	}

	private static void showHelp()
	{
		System.err.println( "Syntax: <offset> FILE..." );
		System.err.println( "" );
		System.err.println( "--offset OFFSET    Date offset to apply, in milliseconds. Or suffix the offset with" );
		System.err.println( "                   'd' for days, 'h' for hours, 'm' for minutes, 's' for seconds." );
		System.err.println( "--date-prefix      Prefix file names with the image date/time." );
	}

	public void setDateOffset( long dateOffset )
	{
		_dateOffset = dateOffset;
	}

	public long getDateOffset()
	{
		return _dateOffset;
	}

	public void setDatePrefix( boolean datePrefix )
	{
		_datePrefix = datePrefix;
	}

	public boolean isDatePrefix()
	{
		return _datePrefix;
	}
}
