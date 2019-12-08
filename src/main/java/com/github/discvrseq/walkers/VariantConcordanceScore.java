package com.github.discvrseq.walkers;

import au.com.bytecode.opencsv.CSVWriter;
import com.github.discvrseq.tools.DiscvrSeqInternalProgramGroup;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.IOUtil;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import org.apache.commons.collections.map.HashedMap;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.engine.*;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.utils.SimpleInterval;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.*;

/**
 * THIS WILL GET PARSED TO MAKE THE HTML DOCS.  Update with a short description and relevant information about the tool
 *
 * <h3>Usage example:</h3>
 * <pre>
 *  java -jar DISCVRseq.jar VariantConcordanceScore \
 *     --ref-sites:SET1 ref1.vcf \
 *     --ref-sites:SET2 ref2.vcf.gz \
 *     -V myVCF.vcf.gz \
 *     -O output.txt
 * </pre>
 */
@DocumentedFeature
@CommandLineProgramProperties(
        summary = "WRITE A SUMMARY",
        oneLineSummary = "WRITE A ONE LINE SUMMARY",
        programGroup = DiscvrSeqInternalProgramGroup.class
)
public class VariantConcordanceScore extends VariantWalker {

    @Argument(fullName = "ref-sites", shortName = "rs", doc = "VCF file containing sites to test.  Must be uniquely named", optional = false)
    public List<FeatureInput<VariantContext>> referenceFiles = new ArrayList<>();

    @Argument(doc="File to which the report should be written", fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME, shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME, optional = false)
    public String outFile = null;

    private Map<SimpleInterval, Map<Allele, Set<String>>> refMap = null;
    private Map<String, Long> totalMarkerByRef = new HashedMap();

    @Override
    public List<SimpleInterval> getTraversalIntervals() {
        if (refMap == null) {
            prepareRefIntervals();
        }

        List<SimpleInterval> ret = new ArrayList<>(refMap.keySet());
        Collections.sort(ret, new Comparator<SimpleInterval>() {
            @Override
            public int compare(SimpleInterval o1, SimpleInterval o2) {
                if (o2 == null) return -1; // nulls last

                int result = o1.getContig().compareTo(o2.getContig());
                if (result == 0) {
                    if (o1.getStart() == o2.getStart()) {
                        result = o1.getEnd() - o2.getEnd();
                    } else {
                        result = o1.getStart() - o2.getStart();
                    }
                }

                return result;
            }
        });

        return ret;
    }

    @Override
    public void onTraversalStart() {
        super.onTraversalStart();

        IOUtil.assertFileIsWritable(new File(outFile));

        prepareRefIntervals();
    }

    private void prepareRefIntervals() {
        Map<SimpleInterval, Map<Allele, Set<String>>> ret = new HashMap<>();

        referenceFiles.stream().forEach(
                f -> {
                    try (VCFFileReader reader = new VCFFileReader(f.toPath()); CloseableIterator<VariantContext> it = reader.iterator()) {
                        while (it.hasNext()) {
                            VariantContext vc = it.next();
                            if (vc.isFiltered()) {
                                throw new IllegalStateException("Reference VCFs should not have filtered variants: " + vc.toStringWithoutGenotypes());
                            }

                            if (vc.getAlternateAlleles().size() > 1) {
                                throw new IllegalStateException("Reference has site with multiple alternates.  Must have either zero or one ALT: " + vc.toStringWithoutGenotypes());
                            }

                            SimpleInterval i = new SimpleInterval(vc.getContig(), vc.getStart(), vc.getEnd());
                            Allele a = vc.isVariant() ? vc.getAlternateAllele(0) : vc.getReference();
                            Map<Allele, Set<String>> map = ret.getOrDefault(i, new HashMap<>());
                            Set<String> sources = map.getOrDefault(a, new HashSet<>());

                            //NOTE: should we throw warning or error if this is >1, indicating references are not mutually exclusive?
                            sources.add(f.getName());
                            map.put(a, sources);
                            ret.put(i, map);

                            totalMarkerByRef.put(f.getName(), totalMarkerByRef.getOrDefault(f.getName(), 0L) + 1);
                        }
                    }
                }
        );

        refMap = ret;
    }

    Map<String, SampleStats> sampleMap = new HashedMap();

    private class SampleStats {
        long totalNoCall = 0;
        Map<String, Long> hits = new HashedMap();

        private Map<String, Double> reportFinalCalls() {
            Map<String, Double> ret = new HashedMap();
            for (String ref : hits.keySet()) {
                double fraction = (double)hits.get(ref) / totalMarkerByRef.get(ref);
                ret.put(ref, fraction);
            }

            return ret;
        }
    }

    @Override
    public void apply(VariantContext vc, ReadsContext readsContext, ReferenceContext referenceContext, FeatureContext featureContext) {
        if (vc.isFiltered()) {
            return;
        }

        SimpleInterval i = new SimpleInterval(vc.getContig(), vc.getStart(), vc.getEnd());
        if (refMap.containsKey(i)) {
            Map<Allele, Set<String>> map = refMap.get(i);

            for (Genotype g : vc.getGenotypes()) {
                SampleStats ss = sampleMap.getOrDefault(g.getSampleName(), new SampleStats());
                if (!g.isCalled()) {
                    ss.totalNoCall += 1;
                    continue;
                }

                for (Allele a : map.keySet()) {
                    if (g.getAlleles().contains(a)) {
                        for (String refHit : map.get(a)) {
                            long total = ss.hits.getOrDefault(refHit, 0L);
                            total++;
                            ss.hits.put(refHit, total);
                        }
                    }
                }

                sampleMap.put(g.getSampleName(), ss);
            }
        }
    }

    @Override
    public Object onTraversalSuccess() {
        //TODO: need to implement some kind of logic to make actual calls per reference
        NumberFormat format = NumberFormat.getInstance();
        format.setMaximumFractionDigits(2);

        try (CSVWriter output = new CSVWriter(IOUtil.openFileForBufferedUtf8Writing(new File(outFile)), '\t', CSVWriter.NO_QUOTE_CHARACTER)) {
            output.writeNext(new String[]{"SampleName", "ReferenceName", "MarkersMatched", "FractionMatched", "TotalMarkersForSet"});

            for (String sample : sampleMap.keySet()) {
                SampleStats ss = sampleMap.get(sample);
                Map<String, Double> refs = ss.reportFinalCalls();
                for (String ref : refs.keySet()) {
                    output.writeNext(new String[]{sample, ref, String.valueOf(ss.hits.get(ref)), format.format(refs.get(ref)), String.valueOf(totalMarkerByRef.get(ref))});
                }
            }

        }
        catch (IOException e) {
            throw new GATKException(e.getMessage());
        }

        //TODO: Consider also tracking stats on specific markers?

        return super.getTraversalIntervals();
    }
}