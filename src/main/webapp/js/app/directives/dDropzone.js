/**
 * Created by kenny on 10/15/14.
 */
angular.module('tatamiApp')
    .directive('file-dropzone',function(){
        return{
            restrict: 'A',
            link: function(scope, elem){
                elem.bind('drop', function(e){
                    e.preventDefault();
                    e.stopPropagation();
                    console.log('File dropped here');
                })
            }
        }
    })
