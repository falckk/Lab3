package amazed.solver;


import amazed.maze.Maze;

import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.locks.*;
import java.util.concurrent.*;

/**
 * <code>ForkJoinSolver4</code> implements a solver for
 * <code>Maze</code> objects using a fork/join multi-thread
 * depth-first search.
 * <p>
 * Instances of <code>ForkJoinSolver42</code> should be run by a
 * <code>ForkJoinPool</code> object.
 */



public class ForkJoinSolver4 extends SequentialSolver{
  private static ConcurrentSkipListSet<Integer> concurrentVisited=new ConcurrentSkipListSet<Integer>();
  /* This does not have to be shared between the threads, we could let every
  thread have a local map and then add all the elements to the parent thread's
  map when joining but this would severly slowdown joining since each element
  might have to be added several time. */
  private static ConcurrentHashMap<Integer, Integer> concurrentPredecessor = new ConcurrentHashMap<Integer, Integer>();
  private static int concurrentStart=-1;
  private static boolean startIsSet=false;
  private static boolean foundGoal=false;
  private static boolean firstComputation=true;
  private static ForkJoinPool pool;
  private boolean hasPlacedPlayer=false;
  private int stepsSinceFork;

    /**
     * Creates a solver that searches in <code>maze</code> from the
     * start node to a goal.
     *
     * @param maze   the maze to be searched
     */
    public ForkJoinSolver4(Maze maze)
    {
        super(maze);
    }

    /**
     * Creates a solver that searches in <code>maze</code> from the
     * start node to a goal, forking after a given number of visited
     * nodes.
     *
     * @param maze        the maze to be searched
     * @param forkAfter   the number of steps (visited nodes) after
     *                    which a parallel task is forked; if
     *                    <code>forkAfter &lt;= 0</code> the solver never
     *                    forks new tasks
     */

    public ForkJoinSolver4(Maze maze, int forkAfter){
      this(maze, forkAfter, 3);
    }
    public ForkJoinSolver4(Maze maze, int forkAfter, int poolSize)
    {
        super(maze);
        if(!startIsSet){
          startIsSet=true;
          concurrentStart=maze.start();
        }
        stepsSinceFork=0;
        this.forkAfter = forkAfter;
        pool=new ForkJoinPool(poolSize);
    }

    /**
     * Searches for and returconcurrenthashmapns the path, as a list of node
     * identifiers, that goes from the start node to a goal node in
     * the maze. If such a path cannot be found (because there are no
     * goals, or all goals are unreacheable), the method returns
     * <code>null</code>.
     *
     * @return   the list of node identifiers from the start node to a
     *           goal node in the maze; <code>null</code> if such a path cannot
     *           be found.
     */
    @Override
    public List<Integer> compute()
    {
        if(firstComputation){
          firstComputation=false;
          return pool.invoke(this);
        }
        return parallelSearch();
    }

    private List<Integer> parallelSearch()
    {
      ArrayList<ForkJoinSolver4> children= new ArrayList<ForkJoinSolver4>();
      int player=-1;
      // start with start node
      frontier.push(start);
      // as long as not all nodes have been processed
      while (!frontier.empty()) {
          if(foundGoal){
            for(ForkJoinSolver4 child: children){
                List<Integer> path = child.join();
                if(path != null){
                  return path;
                }
              }
            return null;
          }
          // get the new node to process
          int current = frontier.pop();
          // if current node has not been visited yet
          if (concurrentVisited.add(current)) {
            // if current node has a goal
            if (maze.hasGoal(current)) {
                // move player to goal
                if(!hasPlacedPlayer){
                  player = maze.newPlayer(current);
                  hasPlacedPlayer=true;
                }
                else{
                  maze.move(player, current);
                }
                // search finished: reconstruct and return path
                foundGoal=true;
                for(ForkJoinSolver4 child: children){
                    List<Integer> path = child.join();
                    if(path != null){
                      return path;
                    }
                  }
                return pathFromTo(concurrentStart, current);
            }
              // move player to current node
              if(!hasPlacedPlayer){
                player = maze.newPlayer(current);
                hasPlacedPlayer=true;
              }
              else{
                maze.move(player, current);
              }
              // for every node nb adjacent to current
              if(stepsSinceFork>=forkAfter){
                boolean hasWork=false;
                for (int nb: maze.neighbors(current)) {
                  // add nb to the nodes to be processed

                  // if nb has not been already visited,
                  // nb can be reached from current (i.e., current is nb's predecessor)
                  if (!concurrentVisited.contains(nb)){
                    if(!hasWork){
                      frontier.push(nb);
                      concurrentPredecessor.put(nb, current);
                      hasWork=true;
                    }
                    else{
                        ForkJoinSolver4 child=new ForkJoinSolver4(maze, forkAfter);
                        child.start=nb;
                        concurrentPredecessor.put(nb, current);
                        children.add(child);
                        pool.execute(child);
                    }
                    }
                    }
                    stepsSinceFork=0;
            }
            else{
            for (int nb: maze.neighbors(current)) {
                // add nb to the nodes to be processed
                frontier.push(nb);
                // if nb has not been already visited,
                // nb can be reached from current (i.e., current is nb's predecessor)
                if (!concurrentVisited.contains(nb))
                    concurrentPredecessor.put(nb, current);
            }
            stepsSinceFork++;
            }
          }
      }
      for(ForkJoinSolver4 child: children){
          List<Integer> path = child.join();
          if(path != null){
            return path;
          }
        }
      // all nodes explored, no goal found
      return null;
    }

    protected List<Integer> pathFromTo(int from, int to) {
        List<Integer> path = new LinkedList<>();
        Integer current = to;
        while (current != from) {
            path.add(current);
            current = concurrentPredecessor.get(current);
            if (current == null)
                return null;
        }
        path.add(from);
        Collections.reverse(path);
        return path;
    }
}
