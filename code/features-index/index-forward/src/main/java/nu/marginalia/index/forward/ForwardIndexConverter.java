package nu.marginalia.index.forward;

import com.upserve.uppend.blobs.NativeIO;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import nu.marginalia.index.journal.reader.IndexJournalReader;
import nu.marginalia.array.LongArray;
import nu.marginalia.index.journal.reader.IndexJournalReaderSingleCompressedFile;
import nu.marginalia.model.idx.DocumentMetadata;
import nu.marginalia.process.control.ProcessHeartbeat;
import nu.marginalia.ranking.DomainRankings;
import org.roaringbitmap.longlong.LongConsumer;
import org.roaringbitmap.longlong.Roaring64Bitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ForwardIndexConverter {

    private final ProcessHeartbeat heartbeat;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final IndexJournalReader journalReader;
    private final Path outputFileDocsId;
    private final Path outputFileDocsData;
    private final DomainRankings domainRankings;


    public ForwardIndexConverter(ProcessHeartbeat heartbeat,
                                 IndexJournalReader journalReader,
                                 Path outputFileDocsId,
                                 Path outputFileDocsData,
                                 DomainRankings domainRankings
                                 ) {
        this.heartbeat = heartbeat;
        this.journalReader = journalReader;
        this.outputFileDocsId = outputFileDocsId;
        this.outputFileDocsData = outputFileDocsData;
        this.domainRankings = domainRankings;
    }

    public enum TaskSteps {
        GET_DOC_IDS,
        GATHER_OFFSETS,
        SUPPLEMENTAL_INDEXES,
        FORCE,
        FINISHED
    }

    public void convert() throws IOException {
        deleteOldFiles();

        logger.info("Domain Rankings size = {}", domainRankings.size());

        try (var progress = heartbeat.createProcessTaskHeartbeat(TaskSteps.class, "forwardIndexConverter")) {
            progress.progress(TaskSteps.GET_DOC_IDS);

            LongArray docsFileId = getDocIds(outputFileDocsId, journalReader);

            progress.progress(TaskSteps.GATHER_OFFSETS);

            // doc ids -> sorted list of ids

            Long2IntOpenHashMap docIdToIdx = new Long2IntOpenHashMap((int) docsFileId.size());
            docsFileId.forEach(0, docsFileId.size(), (pos, val) -> docIdToIdx.put(val, (int) pos));

            progress.progress(TaskSteps.SUPPLEMENTAL_INDEXES);

            // docIdToIdx -> file offset for id

            LongArray docFileData = LongArray.mmapForWriting(outputFileDocsData, ForwardIndexParameters.ENTRY_SIZE * docsFileId.size());

            journalReader.forEach(entry -> {
                long entryOffset = (long) ForwardIndexParameters.ENTRY_SIZE * docIdToIdx.get(entry.docId());

                int ranking = domainRankings.getRanking(entry.domainId());
                long meta = DocumentMetadata.encodeRank(entry.docMeta(), ranking);

                docFileData.set(entryOffset + ForwardIndexParameters.METADATA_OFFSET, meta);
                docFileData.set(entryOffset + ForwardIndexParameters.FEATURES_OFFSET, entry.header.documentFeatures());
            });

            progress.progress(TaskSteps.FORCE);

            docFileData.force();
            docsFileId.force();

            docFileData.advice(NativeIO.Advice.DontNeed);
            docsFileId.advice(NativeIO.Advice.DontNeed);

            progress.progress(TaskSteps.FINISHED);
        } catch (IOException ex) {
            logger.error("Failed to convert", ex);
            throw ex;
        }
    }

    private LongArray getDocIds(Path outputFileDocs, IndexJournalReader journalReader) throws IOException {
        Roaring64Bitmap rbm = new Roaring64Bitmap();
        journalReader.forEachDocId(rbm::add);

        LongArray ret = LongArray.mmapForWriting(outputFileDocs, rbm.getIntCardinality());
        rbm.forEach(new LongConsumer() {
            int offset;
            @Override
            public void accept(long value) {
                ret.set(offset++, value);
            }
        });

        return ret;
    }

    private void deleteOldFiles() throws IOException {
        Files.deleteIfExists(outputFileDocsId);
        Files.deleteIfExists(outputFileDocsData);
    }

}

