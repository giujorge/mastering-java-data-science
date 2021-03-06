package chapter06.embed;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import com.google.common.collect.Ordering;
import com.google.common.collect.Table;

import chapter06.text.Document;
import chapter06.text.Sentence;
import smile.data.SparseDataset;

public class PmiCoOccurrenceMatrix {

    private final Map<String, Integer> tokenToIndex;
    private final List<String> vocabulary;
    private final SparseDataset pmiMatrix; 

    private PmiCoOccurrenceMatrix(Map<String, Integer> tokenToIndex, List<String> vocabulary,
            SparseDataset pmiMatrix) {
        this.tokenToIndex = tokenToIndex;
        this.vocabulary = vocabulary;
        this.pmiMatrix = pmiMatrix;
    }

    public Map<String, Integer> getTokenToIndex() {
        return tokenToIndex;
    }

    public List<String> getVocabulary() {
        return vocabulary;
    }

    public SparseDataset getPmiMatrix() {
        return pmiMatrix;
    }

    public int numberOfWords() {
        return vocabulary.size();
    }

    public static PmiCoOccurrenceMatrix fit(List<Document> documents, int minDf, int window, double smoothing) {
        Multiset<String> docFrequency = documentFrequency(documents, minDf);
        List<String> vocabulary = indexToToken(docFrequency);
        Map<String, Integer> tokenToIndex = tokenToIndex(vocabulary);

        documents = removeInfrequent(documents, tokenToIndex.keySet());

        Multiset<String> counts = unigramCounts(documents);
        Table<String, String, Integer> coOccurrence = coOccurrenceCounts(documents, window);
        SparseDataset pmiMatrix = buildPmiMatrix(vocabulary, tokenToIndex, counts, coOccurrence, smoothing);

        return new PmiCoOccurrenceMatrix(tokenToIndex, vocabulary, pmiMatrix);
    }

    private static List<Document> removeInfrequent(List<Document> documents, Set<String> vocabulary) {
        for (Document doc : documents) {
            for (Sentence sentence : doc.getSentences()) {
                List<String> tokens = sentence.getTokens();
                List<String> filtered = tokens.stream()
                        .filter(t -> vocabulary.contains(t))
                        .collect(Collectors.toList());
                sentence.setTokens(filtered);
            }
        }

        return documents;
    }

    private static Multiset<String> documentFrequency(List<Document> documents, int minDf) {
        Multiset<String> df = HashMultiset.create();
        documents.forEach(doc -> df.addAll(doc.distinctTokens()));
        return Multisets.filter(df, p -> df.count(p) >= minDf);
    }

    private static List<String> indexToToken(Multiset<String> docFrequency) {
        return Ordering.natural().sortedCopy(docFrequency.elementSet());
    }

    public static Map<String, Integer> tokenToIndex(List<String> indexToToken) {
        Map<String, Integer> tokenToIndex = new HashMap<>(indexToToken.size());
        for (int i = 0; i < indexToToken.size(); i++) {
            tokenToIndex.put(indexToToken.get(i), i);
        }
        return tokenToIndex;
    }

    private static Multiset<String> unigramCounts(List<Document> documents) {
        Multiset<String> counts = HashMultiset.create();
        for (Document doc : documents) {
            for (Sentence sentence : doc.getSentences()) {
                counts.addAll(sentence.getTokens());
            }
        }

        return counts;
    }

    private static Table<String, String, Integer> coOccurrenceCounts(List<Document> documents, int window) {
        Table<String, String, Integer> coOccurrence = HashBasedTable.create();

        for (Document doc : documents) {
            for (Sentence sentence : doc.getSentences()) {
                processWindow(sentence, window, coOccurrence);
            }
        }

        return coOccurrence;
    }

    private static void processWindow(Sentence sentence, int window,
            Table<String, String, Integer> coOccurrence) {
        List<String> tokens = sentence.getTokens();

        if (tokens.size() <= 1) {
            return;
        }

        for (int idx = 0; idx < tokens.size(); idx++) {
            String token = tokens.get(idx);
            Map<String, Integer> tokenRow = coOccurrence.row(token);

            for (int otherIdx = idx - window; otherIdx <= idx + window; otherIdx++) {
                if (otherIdx < 0 || otherIdx >= tokens.size()) {
                    continue;
                }
                if (otherIdx == idx) {
                    continue;
                }
                String other = tokens.get(otherIdx);
                int currentCnt = tokenRow.getOrDefault(other, 0);
                tokenRow.put(other, currentCnt + 1);
            }
        }
    }

    private static SparseDataset buildPmiMatrix(List<String> vocabulary, Map<String, Integer> tokenToIndex,
            Multiset<String> counts, Table<String, String, Integer> coOccurrence, double smoothing) {
        int vocabularySize = vocabulary.size();

        double totalNumTokens = counts.size() + vocabularySize * smoothing;
        double logTotalNumTokens = Math.log(totalNumTokens);

        SparseDataset result = new SparseDataset(vocabularySize);

        for (int rowIdx = 0; rowIdx < vocabularySize; rowIdx++) {
            String token = vocabulary.get(rowIdx);

            double mainTokenCount = counts.count(token) + smoothing;
            double logMainTokenCount = Math.log(mainTokenCount);

            Map<String, Integer> tokenCooccurrence = coOccurrence.row(token);

            for (Entry<String, Integer> otherTokenEntry : tokenCooccurrence.entrySet()) {
                String otherToken = otherTokenEntry.getKey();

                double otherTokenCount = counts.count(otherToken) + smoothing;
                double logOtherTokenCount = Math.log(otherTokenCount);
                double coOccCount = otherTokenEntry.getValue() + smoothing;
                double logCoOccCount = Math.log(coOccCount);

                double pmi = logCoOccCount + logTotalNumTokens - logMainTokenCount - logOtherTokenCount;
                if (pmi > 0) {
                    int colIdx = tokenToIndex.get(otherToken);
                    result.set(rowIdx, colIdx, pmi);
                }
            }
        }

        return result;
    }

}
