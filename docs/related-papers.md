# Useful Academic Papers for Implementing Advanced Hash Tables in Kotlin

This document compiles key research papers relevant to implementing modern hash table data
structures in Kotlin, with a focus on **Elastic Hashing**, open addressing techniques, tiny pointers
for memory efficiency, and related collision resolution strategies.

These papers provide the theoretical foundations, algorithms, bounds, and proofs needed for a
high-quality implementation project. All links point to freely downloadable PDFs (arXiv or open
access).

## Core Papers on Elastic Hashing

### 1. Optimal Bounds for Open Addressing Without Reordering

- **Authors**: Martín Farach-Colton, Andrew Krapivin, William Kuszmaul
- **Year**: 2025
- **Description**: The primary paper introducing **Elastic Hashing** and **Funnel Hashing**. It
  presents multi-level partitioning, non-greedy insertion strategies, amortized O(1) and worst-case
  O(log(1/δ)) probe complexities, and matching lower bounds. It disproves Yao's long-standing
  conjecture. Essential reading for the core algorithm.
- **PDF**: [https://arxiv.org/pdf/2501.02305](https://arxiv.org/pdf/2501.02305)
- **HTML Version** (easier to
  read): [https://ar5iv.labs.arxiv.org/html/2501.02305](https://ar5iv.labs.arxiv.org/html/2501.02305)

### 2. Tiny Pointers

- **Authors**: Michael A. Bender, Alex Conway, Martín Farach-Colton, William Kuszmaul, Guido
  Tagliavini
- **Year**: 2021 (arXiv) / 2024 (published)
- **Description**: The paper that inspired Andrew Krapivin's work on dense hash tables. Introduces
  tiny pointers for compressing addresses while maintaining efficiency. Directly relevant for
  memory-optimized data structures and the motivation behind Elastic Hashing.
- **arXiv PDF**: [https://arxiv.org/pdf/2111.12800](https://arxiv.org/pdf/2111.12800)
- **Published Version** (ACM
  TALG): [https://dl.acm.org/doi/pdf/10.1145/3700594](https://dl.acm.org/doi/pdf/10.1145/3700594) (
  or via MIT DSpace if needed)

## Foundational Papers

### 3. Uniform Hashing is Optimal

- **Author**: Andrew Chi-Chih Yao
- **Year**: 1985
- **Description**: Classic paper proving that uniform hashing is asymptotically optimal for open
  addressing in terms of expected retrieval cost. This is the conjecture disproved by the 2025
  Elastic Hashing paper. Crucial for historical and theoretical context.
- **PDF (Stanford Tech Report)
  **: [http://i.stanford.edu/pub/cstr/reports/cs/tr/85/1038/CS-TR-85-1038.pdf](http://i.stanford.edu/pub/cstr/reports/cs/tr/85/1038/CS-TR-85-1038.pdf)
- **ACM Version
  **: [https://dl.acm.org/doi/pdf/10.1145/3828.3836](https://dl.acm.org/doi/pdf/10.1145/3828.3836)

## Surveys and Related Techniques

### 4. Hash Tables as Engines of Randomness at the Limits of Computation: A Unified Review of Algorithms

- **Authors**: Paul A. Gagniuc, Mihai Togan
- **Year**: 2025
- **Description**: Modern comprehensive survey covering linear/quadratic probing, Robin Hood
  hashing, Cuckoo hashing, Hopscotch hashing, and hardware-aware variants. Excellent for
  understanding the broader landscape and comparing techniques.
- **PDF
  **: [https://www.mdpi.com/1999-4893/18/12/804/pdf](https://www.mdpi.com/1999-4893/18/12/804/pdf)

### 5. Hashing Notes / Survey (Carleton University)

- **Author**: Pat Morin et al.
- **Description**: Clear lecture notes covering Robin Hood hashing, Cuckoo hashing, open addressing
  analysis, and practical considerations.
- **PDF
  **: [http://cg.scs.carleton.ca/~morin/teaching/5408/notes/hashing.pdf](http://cg.scs.carleton.ca/~morin/teaching/5408/notes/hashing.pdf)

## Implementation Recommendations for Kotlin Project

- **Start with**: Paper #1 (Elastic Hashing 2025) — implement the multi-level structure, batch
  insertion, non-greedy probing, and slack maintenance.
- **Memory Optimization**: Incorporate ideas from Paper #2 (Tiny Pointers) for compact
  representations.
- **Comparison & Benchmarks**: Use Papers #4 and #5 to implement and benchmark against Linear
  Probing, Robin Hood, and Cuckoo variants.
- **Testing & Correctness**: Implement the probe complexity bounds and compare against theoretical
  predictions.
- **Kotlin-Specific Tips**:
    - Use `inline` classes and value classes for performance.
    - Leverage generics and `expect`/`actual` for multiplatform support.
    - Use `kotlinx.benchmark` for performance testing.
    - Consider `kotlinx.collections.immutable` or custom persistent structures as inspiration.
    - For production quality, study modern implementations like Rust's `hashbrown` / SwissTable (for
      fingerprints, control bytes, SIMD ideas).

## Additional Resources

- **Reference Implementations** (for validation):
    - Rust: [opthash](https://github.com/aaron-ang/opthash) — High-quality ElasticHashMap and
      FunnelHashMap.
    - TypeScript
      Gist: [ElasticHashMap](https://gist.github.com/lifeart/edfb82e3dd3249a503f37b8059b0c164)
- **Related Topics**: Search for "Robin Hood hashing", "Cuckoo hashing", "Hopscotch hashing", and "
  SwissTable" papers for further optimizations.

These papers should provide a solid, citable foundation for your Kotlin data structures library. Add
this file to your project's `docs/` or root directory.

**Last Updated**: June 2026

If you need excerpts, pseudocode translations, or help implementing specific algorithms from these
papers, let me know!
