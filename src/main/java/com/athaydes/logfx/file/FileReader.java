package com.athaydes.logfx.file;

import com.athaydes.logfx.ui.Dialog;
import com.athaydes.logfx.ui.LogView;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 *
 */
public class FileReader {

    private static final int DEFAULT_BUFFER_SIZE = 1024;
    private static final int DEFAULT_MAX_BYTES = DEFAULT_BUFFER_SIZE * 1_000;

    private final long bufferSize;
    private final long maxBytes;

    private final File file;
    private final Consumer<String[]> lineFeed;
    private final AtomicBoolean closed = new AtomicBoolean( false );
    private final Thread watcherThread;
    private final ExecutorService readerThread = Executors.newSingleThreadExecutor();

    public FileReader( File file, Consumer<String[]> lineFeed, long maxBytes, long bufferSize ) {
        if ( bufferSize > maxBytes ) {
            throw new IllegalArgumentException( "bufferSize > maxBytes" );
        }
        if ( bufferSize <= 0 ) {
            throw new IllegalArgumentException( "bufferSize <= 0" );
        }
        this.file = file;
        this.lineFeed = lineFeed;
        this.maxBytes = maxBytes;
        this.bufferSize = bufferSize;
        this.watcherThread = watchFile( file.toPath() );
        watcherThread.setDaemon( true );
    }

    public FileReader( File file, Consumer<String[]> lineFeed ) {
        this( file, lineFeed, DEFAULT_MAX_BYTES, DEFAULT_BUFFER_SIZE );
    }

    /**
     * @param onDone called when done, if all good, true is passed to the function, otherwise, false.
     */
    public void start( Consumer<Boolean> onDone ) {
        readerThread.execute( () -> {
            boolean fileReadOk = onChange();
            if ( fileReadOk ) {
                watcherThread.start();
            }
            onDone.accept( fileReadOk );
        } );
    }

    private Thread watchFile( Path path ) {
        return new Thread( () -> {
            WatchKey watchKey = null;
            try ( WatchService watchService = FileSystems.getDefault().newWatchService() ) {
                watchKey = path.getParent().register( watchService, StandardWatchEventKinds.ENTRY_MODIFY );

                System.out.println( "Watching path " + path.getFileName() );

                while ( !closed.get() ) {
                    WatchKey wk = watchService.take();
                    for ( WatchEvent<?> event : wk.pollEvents() ) {
                        //we only register "ENTRY_MODIFY" so the context is always a Path.
                        Path changed = ( Path ) event.context();
                        if ( !closed.get() && path.getFileName().equals( changed.getFileName() ) ) {
                            readerThread.execute( this::onChange );
                        }
                    }
                    // reset the key
                    boolean valid = wk.reset();
                    if ( !valid ) {
                        System.out.println( "Key has been unregistered!!!!" );
                    }
                }
            } catch ( InterruptedException e ) {
                System.out.println( "Interrupted watching file " + file );
            } catch ( IOException e ) {
                e.printStackTrace();
            } finally {
                if ( watchKey != null ) {
                    watchKey.cancel();
                }
            }
        } );
    }

    private boolean onChange() {
        //TODO implement reading file from any position
        try ( RandomAccessFile reader = new RandomAccessFile( file, "r" ) ) {
            FileChannel channel = reader.getChannel();
            final long channelLength = channel.size();
            if ( channelLength == 0 ) {
                lineFeed.accept( new String[ 0 ] );
                return true;
            }

            lineFeed.accept( joinContiguous( partitions( channel, channelLength ) ) );

            return true;
        } catch ( MalformedInputException e ) {
            Dialog.showConfirmDialog( "Bad encoding." );
        } catch ( Exception e ) {
            e.printStackTrace();
        }
        return false;
    }

    private List<String> partitions( FileChannel channel, long channelLength )
            throws IOException {
        LinkedList<String> partitions = new LinkedList<>();

        long startPosition = channelLength;
        long size = bufferSize;
        final double maxIterations = Math.ceil( ( double ) maxBytes / bufferSize );
        long currentIterations = 0;
        long totalLinesRead = 0;

        do {
            startPosition -= size;

            // compensate for the case where startPosition is negative
            size = Math.min( bufferSize, bufferSize + startPosition );
            long nonNegativeStartPosition = Math.max( 0, startPosition );

            System.out.println( "Mapping file partition from " + nonNegativeStartPosition +
                    " to " + ( nonNegativeStartPosition + size ) );

            MappedByteBuffer mapBuffer = channel.map( FileChannel.MapMode.READ_ONLY,
                    nonNegativeStartPosition, size );

            String partition = readPartition( mapBuffer );
            partitions.addFirst( partition );
            totalLinesRead += linesIn( partition );
            currentIterations += 1;

            // get out if already have enough lines (+ 1 so we can complete the last line if necessary)
            if ( totalLinesRead > LogView.getMaxLines() + 1 ) {
                System.out.println( "Got enough lines already: " + totalLinesRead );
                break;
            }
        } while ( startPosition > 0 && currentIterations < maxIterations );
        System.out.println( "PARTITIONS: " + partitions );
        System.out.println( "Done mapping file in " + currentIterations + " of " + maxIterations + " iterations" );
        return partitions;
    }

    private static String readPartition( MappedByteBuffer buffer )
            throws CharacterCodingException {
        return StandardCharsets.US_ASCII.newDecoder().decode( buffer ).toString();
    }

    private static int linesIn( String partition ) {
        int count = 0;
        for ( char character : partition.toCharArray() ) {
            if ( character == '\n' ) count++;
        }
        return count;
    }

    private static String[] joinContiguous( List<String> partitions ) {
        List<String> result = new ArrayList<>();

        boolean joinPrevious = false;
        for ( int i = partitions.size() - 1; i >= 0; i-- ) {
            String partition = partitions.get( i );
            List<String> lines = new LinkedList<>(
                    Arrays.asList( partition.split( "\n" ) ) );
            Collections.reverse( lines );
            if ( joinPrevious && !partition.endsWith( "\n" ) ) {
                int lastIndex = result.size() - 1;
                result.set( lastIndex, lines.remove( 0 ) + result.get( lastIndex ) );
            }
            result.addAll( lines );
            joinPrevious = !partition.startsWith( "\n" );
        }

        return result.toArray( new String[ result.size() ] );
    }

    public void stop() {
        closed.set( true );
        watcherThread.interrupt();
        readerThread.shutdownNow();
    }

}
