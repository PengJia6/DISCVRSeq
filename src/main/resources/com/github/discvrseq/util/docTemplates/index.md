## Overview
DISCVR-seq Toolkit is a diverse collection of tools for working with sequencing data, developed and maintained by the Bimber Lab, built using the GATK4 engine. The set of tools is analogous to GATK or Picard. A description of all software produced by the Bimber Lab can be found [here](https://bimberlab.github.io).    

## Getting Started
DISCVR-seq Toolkit is a java program distributed as a single JAR.  You can download the latest JAR from our [release page](https://github.com/BimberLab/DISCVRSeq/releases).  Running tools is analogous to GATK4.  

While we recommend [our documentation](toolDoc/index.html) to learn about available tools and options, one can also view a list of tools and/or arguments from the command line:

```

# List available tools:
java -jar DISCVRseq.jar --list 

# View arguments for a specific tool (VariantQC in this example):
java -jar DISCVRseq.jar VariantQC --help

```

Please note that any tool that uses a reference genome requires this genome to be indexed with samtools and to have a sequence dictionary created with Picard.  These requirements are identical to most tools from GATK or Picard.  [See here for more information](toolDoc/resources/genomeRequirements.html) 

## List of Tools
[Our complete list of tools and arguments is available here](toolDoc/index.html). View each tool's page for more information about usage.
