package bachelor;



import niftijio.NiftiVolume;
import org.datavec.api.io.filters.BalancedPathFilter;
import org.datavec.api.io.labels.ParentPathLabelGenerator;
import org.datavec.api.records.reader.RecordReader;
import org.datavec.api.split.FileSplit;
import org.datavec.api.split.InputSplit;
import org.datavec.image.recordreader.ImageRecordReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Random;


/**
 * Created by nicolas on 30.05.17.
 */
public class DeepLREN {
    private static Logger log = LoggerFactory.getLogger(DeepLREN.class);

    private static final String[] allowedFormats = {"nii.gz"};
    protected static final long seed = 12345;
    protected static final Random randGen = new Random(seed);

    public static void run(){
        System.out.println("Starting...");
        System.out.println("Load and prepare data ...");

        File parentDir = new File("src/main/ressources/Dataset");
        FileSplit filesInDir = new FileSplit(parentDir, allowedFormats, randGen);
        ParentPathLabelGenerator labelMaker = new ParentPathLabelGenerator();
        BalancedPathFilter pathFilter = new BalancedPathFilter(randGen, allowedFormats, labelMaker);
        InputSplit[] filesInDirSplit = filesInDir.sample(pathFilter, 80, 20);
        InputSplit trainData = filesInDirSplit[0];
        InputSplit testData = filesInDirSplit[1];

        RecordReader recordReader = new ImageRecordReader(2,2,2,labelMaker);

        try{
            niftijio.NiftiVolume volume = NiftiVolume.read("src/main/resources/Dataset/Male/sub-01_T1w.nii.gz");
            int nx = volume.header.dim[1];
            int ny = volume.header.dim[2];
            int nz = volume.header.dim[3];
            int dim = volume.header.dim[4];

//            double[] flat = new double[nx * ny* nz * dim];
//            int[] shape = {nx};
//            INDArray nDArray = Nd4j.create(flat, shape, 'c');



        }catch(IOException e){
            System.out.println("Error reading NIFTI file");
        }
        //Fin du test

        System.out.println("Configuration Neural Network");
        /*NeuralNetwork neuralNetwork = new NeuralNetwork(1);
        MultiLayerNetwork network = new MultiLayerNetwork(neuralNetwork.getNetwork1());*/
    }
}
