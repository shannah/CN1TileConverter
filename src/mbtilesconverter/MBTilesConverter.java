/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package mbtilesconverter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import org.json.me.JSONObject;
import org.xeustechnologies.jtar.TarEntry;
import org.xeustechnologies.jtar.TarOutputStream;

/**
 *
 * @author shannah
 */
public class MBTilesConverter {
    
    private File inputFile;
    private File databaseFile;
    private File outputFile;
    private File tmpTileFile;
    private Connection conn;
    private TarOutputStream tos;
    private PreparedStatement selectTileDataStmt;
    
    File destinationFile;
    
    
    public MBTilesConverter(String inputPath, String outputPath){
        inputFile = new File(inputPath);
        outputFile = new File(outputPath+".cn1tiles");
       
    }
    
    public MBTilesConverter(String inputPath){
        this(inputPath, inputPath.substring(0, inputPath.indexOf("."))+".cn1tiles");
    }
    
    protected void log(String str){
        
    }
    
    public void convert() throws IOException, SQLException {
        create();
    }
    
    private void create() throws IOException, SQLException{
        
        databaseFile = File.createTempFile(inputFile.getName(), "sqlite");
        tmpTileFile = File.createTempFile(inputFile.getName(), "tmptile");
        BufferedInputStream bis = null;
        GZIPOutputStream gos = null;
        try {
            log("Copying input "+inputFile+" to "+databaseFile);
            copy(inputFile, databaseFile);
            log("Opening tar output stream for "+outputFile);
            tos = new TarOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile)));
            exportTiles();
            exportDatabase();
            tos.close();
            
            
            File gz = new File(outputFile.getAbsolutePath()+".gz");
            gos = new GZIPOutputStream(new FileOutputStream(gz));
            bis = new BufferedInputStream(new FileInputStream(outputFile));
            copy(bis, gos);
            
            gos.flush();
            gos.close();
            bis.close();
            outputFile.delete();
            File dest = new File(gz.getParentFile(), gz.getName().substring(0, gz.getName().indexOf("."))+".cn1tiles");
            dest.delete();
            if ( gz.renameTo(dest)){
                gz = dest;
            }
            destinationFile = gz;
            System.out.println("Output saved in "+gz);
            
        } finally {
            databaseFile.delete();
            tmpTileFile.delete();
            
            try {
                tos.close();
            } catch ( Exception ex){}
            
            try {
                bis.close();
            } catch ( Exception ex ){}
            
            try {
                gos.close();
            } catch ( Exception ex){}
            
        } 
    }
    
    private Connection db(){
        if ( conn == null ){
            try {
                Class.forName("org.sqlite.JDBC");
                conn = DriverManager.getConnection("jdbc:sqlite:"+databaseFile.getAbsolutePath());
            } catch ( Exception ex){
                System.err.println("Failed to connect to database: "+ex.getMessage());
            }
        }
        return conn;
    }
    
    private PreparedStatement getSelectTileDataStmt() throws SQLException{
        if ( selectTileDataStmt == null ){
            selectTileDataStmt = db().prepareStatement("select tile_data from tiles where tile_row=? and tile_column=? and zoom_level=?");
        }
        return selectTileDataStmt;
    }
    
    private void exportTiles() throws IOException, SQLException {
        log("Exporting tiles:");
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = db().createStatement();
            rs = stmt.executeQuery("select tile_row, tile_column, zoom_level from tiles");
            while ( rs.next()){
                exportTile(rs.getInt(1), rs.getInt(2), rs.getInt(3));
            }
            
            
            
        } finally {
            try {
                rs.close();
            } catch ( Exception ex){}
            
            try {
                stmt.close();
            } catch ( Exception ex){}
        }
        
    }
    
    private void exportDatabase() throws IOException, SQLException {
        Statement stmt = null;
        BufferedInputStream bis = null;
        FileOutputStream fos = null;
        HashMap<String,String> metadata = new HashMap<String,String>();
        ResultSet rs = null;
        
        try {
            stmt = db().createStatement();
            rs = stmt.executeQuery("select name,value from metadata");
            while ( rs.next() ){
                metadata.put(rs.getString(1), rs.getString(2));
            }
            rs.close();
            stmt.close();
            
            JSONObject json = new JSONObject(metadata);
            String jsonStr = json.toString();
            byte[] bytes = jsonStr.getBytes();
            fos = new FileOutputStream(tmpTileFile);
            
            bis = new BufferedInputStream(new ByteArrayInputStream(bytes));
            copy(bis, fos);
            bis.close();
            fos.close();
            
            TarEntry entry = new TarEntry(tmpTileFile, "metadata.json");
            bis = new BufferedInputStream(new FileInputStream(tmpTileFile));
            
            tos.putNextEntry(entry);
            copy(bis, tos);
            tos.flush();
            bis.close();
            
        } finally {
            
            try {
                bis.close();
            } catch ( Exception ex){}
            
            try {
                stmt.close();
            } catch ( Exception ex){}
        }
    }
    
    private void exportTile(int row, int col, int zoom) throws IOException, SQLException {
        log("Exporting tile "+row+","+col+"-zoom");
        
        PreparedStatement stmt = getSelectTileDataStmt();
        stmt.setInt(1, row);
        stmt.setInt(2, col);
        stmt.setInt(3, zoom);
        stmt.execute();
        ResultSet rs = null;
        try {
            rs = stmt.getResultSet();

            if ( rs.next() ){
                InputStream is = null;
                FileOutputStream fos = null;
                
                try {
                    is = new BufferedInputStream(new ByteArrayInputStream(rs.getBytes(1)));
                    fos = new FileOutputStream(tmpTileFile);
                    copy(is, fos);
                    is.close();
                    fos.close();
                    is = new BufferedInputStream(new ByteArrayInputStream(rs.getBytes(1)));
                    StringBuilder sb = new StringBuilder("tiles");
                    sb
                            .append("/").append(zoom)
                            .append("/").append(col)
                            .append("/").append(row)
                            .append(".png");
                    log("Creating tar entry "+sb.toString());
                    TarEntry entry = new TarEntry(tmpTileFile, sb.toString());
                    tos.putNextEntry(entry);
                    copy(is, tos);
                    tos.flush();
                    is.close();

                } finally {
                    try {
                        is.close();
                    } catch ( Exception ex){}
                    try {
                        fos.close();
                    } catch ( Exception ex){}
                }
            }
        } finally {
            try {
                rs.close();
            } catch ( Exception ex){}
        }

        
    }
    
    private void copy(File src, File dst) throws IOException {
        FileOutputStream fos = null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(src);
            fos = new FileOutputStream(dst);
            copy(fis,fos);
        } finally {
            try {
                fis.close();
            } catch ( Exception ex){}
            try {
                fos.close();
            } catch ( Exception ex){}
        }
            
    }
    
    private void copy(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[8192];
        int res = -1;
        while (  (res = is.read(buf)) != -1 ){
            os.write(buf, 0, res);
        }
    }

    
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        String input1 = null;
        String input2 = null;
        for (String arg : args ){
            System.out.println("Arg:"+arg);
        }
        if ( args.length < 1 ){
            System.err.println("Usage mbtiles2tar input.mbtiles output.tar");
            System.exit(1);
        }
        
        //System.exit(0);
        MBTilesConverter converter = new MBTilesConverter(args[0], args[1]){

            @Override
            protected void log(String str) {
                System.out.println(str);
            }
            
        };
        try {
            converter.convert();
        } catch (IOException ex) {
            Logger.getLogger(MBTilesConverter.class.getName()).log(Level.SEVERE, null, ex);
        } catch (SQLException ex) {
            Logger.getLogger(MBTilesConverter.class.getName()).log(Level.SEVERE, null, ex);
        }
        
    }
    
}
